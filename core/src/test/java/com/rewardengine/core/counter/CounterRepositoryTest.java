package com.rewardengine.core.counter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rewardengine.common.tenant.TenantContext;
import com.rewardengine.core.TestcontainersConfiguration;
import com.rewardengine.core.campaign.Campaign;
import com.rewardengine.core.campaign.CampaignRepository;
import com.rewardengine.core.campaign.CampaignStatus;
import com.rewardengine.core.tenant.Tenant;
import com.rewardengine.core.tenant.TenantRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CounterRepositoryTest {

	@Autowired
	TenantRepository tenants;

	@Autowired
	CampaignRepository campaigns;

	@Autowired
	CounterRepository counters;

	@Test
	void consumesUpToCapAndRejectsBeyondIt() {
		UUID tenant = newTenant();
		UUID counter = newCounter(tenant, CampaignStatus.ACTIVE).getId();

		assertThat(consume(tenant, counter, 3, 5)).isOne();
		assertThat(consume(tenant, counter, 2, 5)).isOne();
		assertThat(consume(tenant, counter, 1, 5)).isZero();
		assertThat(used(tenant, counter)).isEqualTo(5);
	}

	@Test
	void killSwitchStopsConsumptionEvenWithCapLeft() {
		UUID tenant = newTenant();
		UUID stopped = newCounter(tenant, CampaignStatus.STOPPED).getId();
		UUID paused = newCounter(tenant, CampaignStatus.PAUSED).getId();

		assertThat(consume(tenant, stopped, 1, 100)).isZero();
		assertThat(consume(tenant, paused, 1, 100)).isZero();
		assertThat(used(tenant, stopped)).isZero();
	}

	@Test
	void anotherTenantCannotSeeOrConsumeTheCounter() {
		UUID owner = newTenant();
		UUID other = newTenant();
		UUID counter = newCounter(owner, CampaignStatus.ACTIVE).getId();

		assertThat(TenantContext.callAs(other, () -> counters.findById(counter))).isEmpty();
		assertThat(consume(other, counter, 1, 100)).isZero();
		assertThat(used(owner, counter)).isZero();
	}

	@Test
	void staleEntityCannotWriteBackOldUsage() {
		UUID tenant = newTenant();
		Counter stale = newCounter(tenant, CampaignStatus.ACTIVE);

		assertThat(consume(tenant, stale.getId(), 1, 10)).isOne();

		assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> counters.save(stale)))
				.isInstanceOf(ObjectOptimisticLockingFailureException.class);
		assertThat(used(tenant, stale.getId())).isOne();
	}

	@Test
	void concurrentCallersNeverExceedCap() throws Exception {
		UUID tenant = newTenant();
		UUID counter = newCounter(tenant, CampaignStatus.ACTIVE).getId();
		List<Callable<Integer>> callers = IntStream.range(0, 20)
				.<Callable<Integer>>mapToObj(i -> () -> consume(tenant, counter, 1, 10))
				.toList();

		int granted = 0;
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (Future<Integer> result : executor.invokeAll(callers)) {
				granted += result.get();
			}
		}

		assertThat(granted).isEqualTo(10);
		assertThat(used(tenant, counter)).isEqualTo(10);
	}

	@Test
	void tenantScopedWriteWithoutTenantFails() {
		Campaign campaign = new Campaign();
		campaign.setName("no tenant bound");

		assertThatThrownBy(() -> campaigns.save(campaign)).isInstanceOf(DataIntegrityViolationException.class);
	}

	private UUID newTenant() {
		Tenant tenant = new Tenant();
		tenant.setCode("t-" + UUID.randomUUID());
		tenant.setName("Test tenant");
		tenant.setTimeZone("UTC");
		tenant.setCurrency("IDR");
		return tenants.save(tenant).getId();
	}

	private Counter newCounter(UUID tenant, CampaignStatus status) {
		return TenantContext.callAs(tenant, () -> {
			Campaign campaign = new Campaign();
			campaign.setName("Campaign");
			campaign.setStatus(status);
			Counter counter = new Counter();
			counter.setCampaignId(campaigns.save(campaign).getId());
			counter.setName("quota");
			return counters.save(counter);
		});
	}

	private int consume(UUID tenant, UUID counter, long amount, long cap) {
		return TenantContext.callAs(tenant, () -> counters.consumeAllOrNothing(counter, amount, cap));
	}

	private long used(UUID tenant, UUID counter) {
		return TenantContext.callAs(tenant, () -> counters.findById(counter).orElseThrow().getUsed());
	}
}
