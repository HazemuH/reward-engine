package com.rewardengine.core.counter;

import static com.rewardengine.core.TestSupport.assertRejected;
import static com.rewardengine.core.TestSupport.moveTo;
import static com.rewardengine.core.TestSupport.newTenant;
import static com.rewardengine.core.TestSupport.race;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rewardengine.common.error.BusinessRuleException;
import com.rewardengine.common.tenant.TenantContext;
import com.rewardengine.core.TestcontainersConfiguration;
import com.rewardengine.core.campaign.CampaignService;
import com.rewardengine.core.campaign.CampaignVersion;
import com.rewardengine.core.campaign.CampaignVersionRepository;
import com.rewardengine.core.campaign.CampaignVersionStatus;
import com.rewardengine.core.campaign.DraftContent;
import com.rewardengine.core.tenant.TenantRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CounterDefServiceTest {

	private static final DraftContent EMPTY = new DraftContent("{}", null, Set.of("MOBILE"));
	private static final DraftContent WITH_BENEFIT = new DraftContent(
			"{\"benefits\": [{\"type\": \"POINT\", \"amount\": 50}]}", null, Set.of("MOBILE"));

	private static final CounterLimit QUOTA = new CounterLimit("quota", CounterScope.CAMPAIGN, CounterUnit.COUNT,
			CounterPeriod.CAMPAIGN, CounterMode.ALL_OR_NOTHING, 100);
	private static final CounterLimit BUDGET = new CounterLimit("budget", CounterScope.CAMPAIGN, CounterUnit.POINT,
			CounterPeriod.CAMPAIGN, CounterMode.ALL_OR_NOTHING, 5_000);
	private static final CounterLimit PER_USER = new CounterLimit("perUserMonthly", CounterScope.USER,
			CounterUnit.POINT, CounterPeriod.MONTH, CounterMode.PARTIAL, 400);

	@Autowired
	TenantRepository tenants;

	@Autowired
	CampaignService campaigns;

	@Autowired
	CampaignVersionRepository versions;

	@Autowired
	CounterDefService service;

	@Test
	void replaceStoresLimitsInListOrderAndBumpsVersion() {
		UUID tenant = newTenant(tenants);
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> campaigns.create("Campaign", EMPTY));

		CampaignVersion saved = replace(tenant, v1, QUOTA, BUDGET);

		assertThat(saved.getVersion()).isEqualTo(v1.getVersion() + 1);
		assertThat(limits(tenant, v1.getId())).containsExactly(QUOTA, BUDGET);
		assertThat(TenantContext.callAs(tenant, () -> service.limits(v1.getId())))
				.extracting(CounterDef::getSortOrder).containsExactly(0, 1);
	}

	@Test
	void reorderingAndKeepingNamesInOneReplaceDoesNotTripTheUniqueName() {
		UUID tenant = newTenant(tenants);
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> campaigns.create("Campaign", EMPTY));
		CampaignVersion saved = replace(tenant, v1, QUOTA, BUDGET);
		CounterLimit raisedBudget = new CounterLimit("budget", CounterScope.CAMPAIGN, CounterUnit.POINT,
				CounterPeriod.CAMPAIGN, CounterMode.ALL_OR_NOTHING, 9_000);

		replace(tenant, saved, PER_USER, raisedBudget, QUOTA);

		assertThat(limits(tenant, v1.getId())).containsExactly(PER_USER, raisedBudget, QUOTA);
	}

	@Test
	void unchangedListWritesNothingAndKeepsVersion() {
		UUID tenant = newTenant(tenants);
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> campaigns.create("Campaign", EMPTY));
		CampaignVersion saved = replace(tenant, v1, QUOTA, BUDGET);
		List<UUID> ids = TenantContext.callAs(tenant, () -> service.limits(v1.getId())).stream()
				.map(CounterDef::getId).toList();

		CampaignVersion resaved = replace(tenant, saved, QUOTA, BUDGET);

		assertThat(resaved.getVersion()).isEqualTo(saved.getVersion());
		assertThat(TenantContext.callAs(tenant, () -> service.limits(v1.getId())))
				.extracting(CounterDef::getId).containsExactlyElementsOf(ids);
	}

	@Test
	void limitsAndContentEditsShareTheVersionsOptimisticLock() {
		UUID tenant = newTenant(tenants);
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> campaigns.create("Campaign", EMPTY));

		replace(tenant, v1, QUOTA);
		assertThatThrownBy(() -> TenantContext.callAs(tenant,
				() -> campaigns.editDraft(v1.getId(), v1.getVersion(), WITH_BENEFIT)))
				.isInstanceOf(ObjectOptimisticLockingFailureException.class);

		CampaignVersion current = TenantContext.callAs(tenant, () -> campaigns.getVersion(v1.getId()));
		TenantContext.callAs(tenant, () -> campaigns.editDraft(v1.getId(), current.getVersion(), WITH_BENEFIT));
		assertThatThrownBy(() -> replace(tenant, current, BUDGET))
				.isInstanceOf(ObjectOptimisticLockingFailureException.class);
		assertThat(limits(tenant, v1.getId())).containsExactly(QUOTA);
	}

	@Test
	void frozenVersionIsRejectedWithACode() {
		UUID tenant = newTenant(tenants);
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> campaigns.create("Campaign", EMPTY));
		CampaignVersion pending = moveTo(versions, tenant, v1.getId(), CampaignVersionStatus.PENDING_APPROVAL);

		assertRejected(tenant, () -> service.replaceDraftLimits(pending.getId(), pending.getVersion(), List.of(QUOTA)),
				"VERSION_NOT_EDITABLE");
		// reported ahead of the list's own problems
		List<CounterLimit> invalid = List.of(new CounterLimit("bad name", null, null, null, null, 0));
		assertRejected(tenant, () -> service.replaceDraftLimits(pending.getId(), pending.getVersion(), invalid),
				"VERSION_NOT_EDITABLE");
		assertThat(limits(tenant, v1.getId())).isEmpty();
	}

	@Test
	void invalidLimitsAreRejectedWithEveryViolationAndNothingIsWritten() {
		UUID tenant = newTenant(tenants);
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> campaigns.create("Campaign", EMPTY));
		List<CounterLimit> invalid = Arrays.asList(
				QUOTA,
				new CounterLimit("quota", CounterScope.USER, CounterUnit.COUNT, CounterPeriod.DAY,
						CounterMode.PARTIAL, 0),
				new CounterLimit("bad name", null, null, null, null, 1),
				null);

		assertThatThrownBy(() -> TenantContext.callAs(tenant,
				() -> service.replaceDraftLimits(v1.getId(), v1.getVersion(), invalid)))
				.isInstanceOfSatisfying(BusinessRuleException.class, e -> {
					assertThat(e.getCode()).isEqualTo("COUNTER_LIMITS_INVALID");
					assertThat(e.getDetails()).containsExactly(
							"counters[1].name: \"quota\" is used by another counter",
							"counters[1].mode: PARTIAL needs a MONEY or POINT unit",
							"counters[1].cap: must be greater than 0",
							"counters[2].name: a letter followed by letters, digits or '_' (at most 100)",
							"counters[2].scope: required",
							"counters[2].unit: required",
							"counters[2].period: required",
							"counters[2].mode: required",
							"counters[3]: must not be null");
				});
		assertThat(limits(tenant, v1.getId())).isEmpty();
		assertThat(TenantContext.callAs(tenant, () -> campaigns.getVersion(v1.getId())).getVersion())
				.isEqualTo(v1.getVersion());
	}

	@Test
	void concurrentReplacesOfTheSameDraftProduceOneWinner() throws Exception {
		UUID tenant = newTenant(tenants);
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> campaigns.create("Campaign", EMPTY));
		replace(tenant, v1, QUOTA);
		CampaignVersion read = TenantContext.callAs(tenant, () -> campaigns.getVersion(v1.getId()));
		List<Callable<CampaignVersion>> editors = IntStream.range(0, 10)
				.<Callable<CampaignVersion>>mapToObj(i -> () -> replace(tenant, read, QUOTA,
						new CounterLimit("budget", CounterScope.CAMPAIGN, CounterUnit.POINT, CounterPeriod.CAMPAIGN,
								CounterMode.ALL_OR_NOTHING, 1_000 + i)))
				.toList();

		List<Throwable> failures = race(editors);

		assertThat(failures).hasSize(9).allMatch(ObjectOptimisticLockingFailureException.class::isInstance);
		assertThat(limits(tenant, v1.getId())).hasSize(2);
		assertThat(TenantContext.callAs(tenant, () -> campaigns.getVersion(v1.getId())).getVersion())
				.isEqualTo(read.getVersion() + 1);
	}

	@Test
	void anotherTenantCannotSeeOrReplaceTheLimits() {
		UUID owner = newTenant(tenants);
		UUID other = newTenant(tenants);
		CampaignVersion v1 = TenantContext.callAs(owner, () -> campaigns.create("Campaign", EMPTY));
		CampaignVersion saved = replace(owner, v1, QUOTA);

		assertRejected(other, () -> service.limits(v1.getId()), "VERSION_NOT_FOUND");
		assertRejected(other, () -> service.replaceDraftLimits(v1.getId(), saved.getVersion(), List.of(BUDGET)),
				"VERSION_NOT_FOUND");
		assertThat(limits(owner, v1.getId())).containsExactly(QUOTA);
		assertThat(TenantContext.callAs(owner, () -> campaigns.getVersion(v1.getId())).getVersion())
				.isEqualTo(saved.getVersion());
	}

	private CampaignVersion replace(UUID tenant, CampaignVersion read, CounterLimit... limits) {
		return TenantContext.callAs(tenant,
				() -> service.replaceDraftLimits(read.getId(), read.getVersion(), List.of(limits)));
	}

	private List<CounterLimit> limits(UUID tenant, UUID versionId) {
		return TenantContext.callAs(tenant, () -> service.limits(versionId)).stream().map(CounterLimit::of).toList();
	}
}
