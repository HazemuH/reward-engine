package com.rewardengine.core.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rewardengine.common.error.BusinessRuleException;
import com.rewardengine.common.tenant.TenantContext;
import com.rewardengine.core.TestcontainersConfiguration;
import com.rewardengine.core.counter.Counter;
import com.rewardengine.core.counter.CounterDef;
import com.rewardengine.core.counter.CounterDefRepository;
import com.rewardengine.core.counter.CounterMode;
import com.rewardengine.core.counter.CounterPeriod;
import com.rewardengine.core.counter.CounterRepository;
import com.rewardengine.core.counter.CounterScope;
import com.rewardengine.core.counter.CounterUnit;
import com.rewardengine.core.tenant.Tenant;
import com.rewardengine.core.tenant.TenantRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CampaignServiceTest {

	// Written in jsonb's canonical spacing so a round-trip through Postgres compares equal.
	private static final DraftContent V1 = new DraftContent("{\"conditions\": []}", null, Set.of("MOBILE"));
	private static final DraftContent V2 = new DraftContent("{\"conditions\": [{\"attr\": \"segment\"}]}",
			"return decision", Set.of("MOBILE", "WEB"));

	@Autowired
	TenantRepository tenants;

	@Autowired
	CampaignService service;

	@Autowired
	CampaignVersionRepository versions;

	@Autowired
	CounterDefRepository counterDefs;

	@Autowired
	CampaignRepository campaigns;

	@Autowired
	CounterRepository counters;

	@Autowired
	PlatformTransactionManager txManager;

	@Autowired
	VersionCarryOver carryOver;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void createStartsCampaignAndFirstVersionAsDraft() {
		UUID tenant = newTenant();

		CampaignVersion v1 = TenantContext.callAs(tenant, () -> service.create("  Weekend cashback ", V1));

		Campaign campaign = TenantContext.callAs(tenant, () -> service.get(v1.getCampaignId()));
		assertThat(campaign.getName()).isEqualTo("Weekend cashback");
		assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.DRAFT);
		assertThat(v1.getVersionNumber()).isOne();
		assertThat(v1.getStatus()).isEqualTo(CampaignVersionStatus.DRAFT);
		assertThat(v1.getChannels()).containsExactly("MOBILE");
		assertThat(TenantContext.callAs(tenant, () -> service.list(Pageable.unpaged())))
				.extracting(Campaign::getId).containsExactly(campaign.getId());
	}

	@Test
	void editDraftReplacesContentAndBumpsVersion() {
		UUID tenant = newTenant();
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> service.create("Campaign", V1));

		CampaignVersion edited = TenantContext.callAs(tenant, () -> service.editDraft(v1.getId(), v1.getVersion(), V2));

		assertThat(edited.getVersion()).isGreaterThan(v1.getVersion());
		CampaignVersion reloaded = TenantContext.callAs(tenant, () -> service.versions(v1.getCampaignId())).getFirst();
		assertThat(reloaded.getOptionalScript()).isEqualTo("return decision");
		assertThat(reloaded.getChannels()).containsExactlyInAnyOrder("MOBILE", "WEB");
	}

	@Test
	void staleExpectedVersionIsRejected() {
		UUID tenant = newTenant();
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> service.create("Campaign", V1));
		TenantContext.callAs(tenant, () -> service.editDraft(v1.getId(), v1.getVersion(), V2));

		assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> service.editDraft(v1.getId(), v1.getVersion(), V1)))
				.isInstanceOf(ObjectOptimisticLockingFailureException.class);
		assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> service.rename(v1.getCampaignId(), 99, "x")))
				.isInstanceOf(ObjectOptimisticLockingFailureException.class);
	}

	@Test
	void contentIsFrozenOnceVersionLeavesDraft() {
		UUID tenant = newTenant();
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> service.create("Campaign", V1));
		CampaignVersion pending = moveTo(tenant, v1.getId(), CampaignVersionStatus.PENDING_APPROVAL);

		assertThatThrownBy(() -> TenantContext.callAs(tenant,
				() -> service.editDraft(pending.getId(), pending.getVersion(), V2)))
				.isInstanceOfSatisfying(BusinessRuleException.class,
						e -> assertThat(e.getCode()).isEqualTo("VERSION_NOT_EDITABLE"));
		assertThat(TenantContext.callAs(tenant, () -> service.getVersion(v1.getId())).getRawDefinition())
				.isEqualTo(V1.rawDefinition());
	}

	@Test
	void databaseRejectsContentChangesOnAFrozenVersionEvenOutsideJpa() {
		UUID tenant = newTenant();
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> service.create("Campaign", V1));
		moveTo(tenant, v1.getId(), CampaignVersionStatus.ACTIVE);

		assertThatThrownBy(() -> jdbc.update(
				"update campaign_version set raw_definition = '{}'::jsonb where id = ?", v1.getId()))
				.hasMessageContaining("frozen");
		assertThatThrownBy(() -> jdbc.update(
				"update campaign_version set optional_script = 'x' where id = ?", v1.getId()))
				.hasMessageContaining("frozen");
		assertThatThrownBy(() -> jdbc.update(
				"insert into campaign_version_channel (campaign_version_id, channel) values (?, 'ATM')", v1.getId()))
				.hasMessageContaining("frozen");
		assertThatThrownBy(() -> jdbc.update(
				"delete from campaign_version_channel where campaign_version_id = ?", v1.getId()))
				.hasMessageContaining("frozen");
	}

	@Test
	void frozenVersionCannotGoBackToDraftOrHaveItsCapsChanged() {
		UUID tenant = newTenant();
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> service.create("Campaign", V1));
		addCounterDef(tenant, v1.getId(), 100);
		moveTo(tenant, v1.getId(), CampaignVersionStatus.ACTIVE);

		assertThatThrownBy(() -> jdbc.update(
				"update campaign_version set status = 'DRAFT' where id = ?", v1.getId()))
				.hasMessageContaining("frozen");
		assertThatThrownBy(() -> jdbc.update(
				"update counter_def set cap = 1000000 where campaign_version_id = ?", v1.getId()))
				.hasMessageContaining("frozen");
		assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> {
			CounterDef extra = new CounterDef();
			extra.setCampaignVersionId(v1.getId());
			return counterDefs.saveAndFlush(fill(extra, "extra", 5));
		})).hasMessageContaining("frozen");
	}

	@Test
	void rowsCannotBeMovedOutOfAFrozenVersionNorTheVersionRenumbered() {
		UUID tenant = newTenant();
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> service.create("Campaign", V1));
		addCounterDef(tenant, v1.getId(), 100);
		moveTo(tenant, v1.getId(), CampaignVersionStatus.ACTIVE);
		CampaignVersion v2 = TenantContext.callAs(tenant, () -> service.openDraft(v1.getCampaignId()));

		assertThatThrownBy(() -> jdbc.update(
				"update counter_def set campaign_version_id = ? where campaign_version_id = ?", v2.getId(), v1.getId()))
				.hasMessageContaining("frozen");
		assertThatThrownBy(() -> jdbc.update(
				"update campaign_version_channel set campaign_version_id = ? where campaign_version_id = ?",
				v2.getId(), v1.getId()))
				.hasMessageContaining("frozen");
		assertThatThrownBy(() -> jdbc.update(
				"update counter_def set campaign_version_id = ? where campaign_version_id = ?", v1.getId(), v2.getId()))
				.hasMessageContaining("frozen");
		assertThatThrownBy(() -> jdbc.update(
				"update campaign_version set version_number = 9 where id = ?", v2.getId()))
				.hasMessageContaining("renumbered");
		UUID otherCampaign = TenantContext.callAs(tenant, () -> service.create("Other", V1)).getCampaignId();
		assertThatThrownBy(() -> jdbc.update(
				"update campaign_version set campaign_id = ? where id = ?", otherCampaign, v2.getId()))
				.hasMessageContaining("moved");
	}

	@Test
	void concurrentChildWritesThenVersionUpdatesDoNotDeadlock() throws Exception {
		UUID tenant = newTenant();
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> service.create("Campaign", V1));
		TransactionTemplate tx = new TransactionTemplate(txManager);
		List<Callable<Integer>> writers = IntStream.range(0, 10)
				.<Callable<Integer>>mapToObj(i -> () -> TenantContext.callAs(tenant, () -> tx.execute(status -> {
					CounterDef def = new CounterDef();
					def.setCampaignVersionId(v1.getId());
					counterDefs.saveAndFlush(fill(def, "quota-" + i, 10));
					return jdbc.update("update campaign_version set optional_script = ? where id = ?",
							"writer " + i, v1.getId());
				})))
				.toList();

		assertThat(race(writers)).isEmpty();
		assertThat(TenantContext.callAs(tenant,
				() -> counterDefs.findByCampaignVersionIdOrderBySortOrderAsc(v1.getId()))).hasSize(10);
	}

	@Test
	void newVersionLockDoesNotBlockCounterInsertsForTheCampaign() throws Exception {
		UUID tenant = newTenant();
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> service.create("Campaign", V1));
		ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		try {
			// state is read while the lock is still held; after commit the insert would finish either way
			Future.State whileLocked = TenantContext.callAs(tenant, () -> new TransactionTemplate(txManager).execute(tx -> {
				campaigns.lockForNewVersion(v1.getCampaignId()).orElseThrow();
				Future<Counter> concurrent = executor.submit(() -> TenantContext.callAs(tenant, () -> {
					Counter counter = new Counter();
					counter.setCampaignId(v1.getCampaignId());
					counter.setName("quota");
					return counters.saveAndFlush(counter);
				}));
				awaitQuietly(concurrent);
				return concurrent.state();
			}));

			assertThat(whileLocked).isEqualTo(Future.State.SUCCESS);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void openDraftCarriesCounterLimitsForward() {
		UUID tenant = newTenant();
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> service.create("Campaign", V1));
		addCounterDef(tenant, v1.getId(), 100);
		moveTo(tenant, v1.getId(), CampaignVersionStatus.ACTIVE);

		CampaignVersion v2 = TenantContext.callAs(tenant, () -> service.openDraft(v1.getCampaignId()));

		List<CounterDef> copied = TenantContext.callAs(tenant,
				() -> counterDefs.findByCampaignVersionIdOrderBySortOrderAsc(v2.getId()));
		assertThat(copied).singleElement().satisfies(def -> {
			assertThat(def.getName()).isEqualTo("quota");
			assertThat(def.getCap()).isEqualTo(100);
			assertThat(def.getMode()).isEqualTo(CounterMode.ALL_OR_NOTHING);
		});
		assertThat(TenantContext.callAs(tenant,
				() -> counterDefs.findByCampaignVersionIdOrderBySortOrderAsc(v1.getId()))).hasSize(1);
	}

	@Test
	void carryOverRefusesToRunOutsideTheDraftTransaction() {
		UUID tenant = newTenant();
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> service.create("Campaign", V1));

		assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> carryOver.carryOver(v1.getId(), UUID.randomUUID())))
				.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void blankOrOverlongNameIsRejectedWithACode() {
		UUID tenant = newTenant();

		assertRejected(tenant, () -> service.create("   ", V1), "CAMPAIGN_NAME_INVALID");
		assertRejected(tenant, () -> service.create("x".repeat(201), V1), "CAMPAIGN_NAME_INVALID");
	}

	@Test
	void unchangedSaveDoesNotBumpVersion() {
		UUID tenant = newTenant();
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> service.create("Campaign", V2));

		CampaignVersion saved = TenantContext.callAs(tenant, () -> service.editDraft(v1.getId(), v1.getVersion(), V2));

		assertThat(saved.getVersion()).isEqualTo(v1.getVersion());
	}

	@Test
	void openDraftCopiesLatestVersionAsNextNumber() {
		UUID tenant = newTenant();
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> service.create("Campaign", V1));
		TenantContext.callAs(tenant, () -> service.editDraft(v1.getId(), v1.getVersion(), V2));
		moveTo(tenant, v1.getId(), CampaignVersionStatus.ACTIVE);

		CampaignVersion v2 = TenantContext.callAs(tenant, () -> service.openDraft(v1.getCampaignId()));

		assertThat(v2.getVersionNumber()).isEqualTo(2);
		assertThat(v2.getStatus()).isEqualTo(CampaignVersionStatus.DRAFT);
		assertThat(v2.getRawDefinition()).isEqualTo(V2.rawDefinition());
		assertThat(v2.getOptionalScript()).isEqualTo(V2.optionalScript());
		assertThat(v2.getChannels()).containsExactlyInAnyOrderElementsOf(V2.channels());
	}

	@Test
	void secondOpenDraftIsRejectedWhileOneIsOpen() {
		UUID tenant = newTenant();
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> service.create("Campaign", V1));

		assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> service.openDraft(v1.getCampaignId())))
				.isInstanceOfSatisfying(BusinessRuleException.class,
						e -> assertThat(e.getCode()).isEqualTo("DRAFT_ALREADY_OPEN"));
	}

	@Test
	void databaseRejectsASecondDraftEvenIfTheServiceIsBypassed() {
		UUID tenant = newTenant();
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> service.create("Campaign", V1));
		CampaignVersion bypass = new CampaignVersion();
		bypass.setCampaignId(v1.getCampaignId());
		bypass.setVersionNumber(2);
		bypass.editDraft(V1);

		assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> versions.saveAndFlush(bypass)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void concurrentEditsOfTheSameVersionProduceOneWinner() throws Exception {
		UUID tenant = newTenant();
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> service.create("Campaign", V1));
		List<Callable<CampaignVersion>> editors = IntStream.range(0, 10)
				.<Callable<CampaignVersion>>mapToObj(i -> () -> TenantContext.callAs(tenant,
						() -> service.editDraft(v1.getId(), v1.getVersion(), V2)))
				.toList();

		List<Throwable> failures = race(editors);

		assertThat(failures).hasSize(9).allMatch(ObjectOptimisticLockingFailureException.class::isInstance);
		assertThat(TenantContext.callAs(tenant, () -> service.getVersion(v1.getId())).getVersion())
				.isEqualTo(v1.getVersion() + 1);
	}

	@Test
	void concurrentOpenDraftCreatesExactlyOneDraft() throws Exception {
		UUID tenant = newTenant();
		CampaignVersion v1 = TenantContext.callAs(tenant, () -> service.create("Campaign", V1));
		moveTo(tenant, v1.getId(), CampaignVersionStatus.ACTIVE);
		List<Callable<CampaignVersion>> openers = IntStream.range(0, 10)
				.<Callable<CampaignVersion>>mapToObj(i -> () -> TenantContext.callAs(tenant,
						() -> service.openDraft(v1.getCampaignId())))
				.toList();

		List<Throwable> failures = race(openers);

		assertThat(failures).hasSize(9).allSatisfy(e -> assertThat(e)
				.isInstanceOfSatisfying(BusinessRuleException.class,
						b -> assertThat(b.getCode()).isEqualTo("DRAFT_ALREADY_OPEN")));
		assertThat(TenantContext.callAs(tenant, () -> service.versions(v1.getCampaignId())))
				.extracting(CampaignVersion::getVersionNumber).containsExactly(1, 2);
	}

	@Test
	void anotherTenantCannotSeeOrChangeTheCampaign() {
		UUID owner = newTenant();
		UUID other = newTenant();
		CampaignVersion v1 = TenantContext.callAs(owner, () -> service.create("Campaign", V1));

		assertThat(TenantContext.callAs(other, () -> service.list(Pageable.unpaged()))).isEmpty();
		assertRejected(other, () -> service.get(v1.getCampaignId()), "CAMPAIGN_NOT_FOUND");
		assertRejected(other, () -> service.versions(v1.getCampaignId()), "CAMPAIGN_NOT_FOUND");
		assertRejected(other, () -> service.rename(v1.getCampaignId(), 0, "hijacked"), "CAMPAIGN_NOT_FOUND");
		assertRejected(other, () -> service.openDraft(v1.getCampaignId()), "CAMPAIGN_NOT_FOUND");
		assertRejected(other, () -> service.editDraft(v1.getId(), v1.getVersion(), V2), "VERSION_NOT_FOUND");
		assertThat(TenantContext.callAs(owner, () -> service.getVersion(v1.getId())).getRawDefinition())
				.isEqualTo(V1.rawDefinition());
	}

	/** Waits while the caller still holds its lock; a blocked insert shows up as not done. */
	private static void awaitQuietly(Future<?> future) {
		try {
			future.get(5, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			// still blocked: the caller asserts on the future's state
		} catch (ExecutionException e) {
			throw new AssertionError("concurrent write failed rather than blocked", e.getCause());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void addCounterDef(UUID tenant, UUID versionId, long cap) {
		TenantContext.callAs(tenant, () -> {
			CounterDef def = new CounterDef();
			def.setCampaignVersionId(versionId);
			return counterDefs.saveAndFlush(fill(def, "quota", cap));
		});
	}

	private static CounterDef fill(CounterDef def, String name, long cap) {
		def.setName(name);
		def.setScope(CounterScope.CAMPAIGN);
		def.setUnit(CounterUnit.COUNT);
		def.setPeriod(CounterPeriod.CAMPAIGN);
		def.setMode(CounterMode.ALL_OR_NOTHING);
		def.setCap(cap);
		def.setSortOrder(0);
		return def;
	}

	private static void assertRejected(UUID tenant, ScopedValue.CallableOp<?, RuntimeException> op, String code) {
		assertThatThrownBy(() -> TenantContext.callAs(tenant, op))
				.isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo(code));
	}

	/** Runs every caller concurrently; returns the failures (one success expected per test). */
	private static <T> List<Throwable> race(List<Callable<T>> callers) throws InterruptedException {
		List<Throwable> failures = new ArrayList<>();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (Future<T> result : executor.invokeAll(callers)) {
				try {
					result.get();
				} catch (ExecutionException e) {
					failures.add(e.getCause());
				}
			}
		}
		return failures;
	}

	/** Stands in for the approval flow (M7), which doesn't exist yet. */
	private CampaignVersion moveTo(UUID tenant, UUID versionId, CampaignVersionStatus status) {
		return TenantContext.callAs(tenant, () -> {
			CampaignVersion version = versions.findById(versionId).orElseThrow();
			version.setStatus(status);
			version.setApprovedBy("checker");
			version.setApprovedAt(Instant.now());
			return versions.saveAndFlush(version);
		});
	}

	private UUID newTenant() {
		Tenant tenant = new Tenant();
		tenant.setCode("t-" + UUID.randomUUID());
		tenant.setName("Test tenant");
		tenant.setTimeZone("UTC");
		tenant.setCurrency("IDR");
		return tenants.save(tenant).getId();
	}
}
