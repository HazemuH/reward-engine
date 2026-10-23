package com.rewardengine.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rewardengine.common.error.BusinessRuleException;
import com.rewardengine.common.tenant.TenantContext;
import com.rewardengine.core.campaign.CampaignVersion;
import com.rewardengine.core.campaign.CampaignVersionRepository;
import com.rewardengine.core.campaign.CampaignVersionStatus;
import com.rewardengine.core.tenant.Tenant;
import com.rewardengine.core.tenant.TenantRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Helpers shared by the integration tests. Static and handed their repositories,
 * rather than a bean, so no test's Spring context differs from another's and the
 * one cached context (and container) keeps serving the whole run.
 */
public final class TestSupport {

	private TestSupport() {
	}

	/** Tests share one database, so each makes its own tenant instead of cleaning up. */
	public static UUID newTenant(TenantRepository tenants) {
		Tenant tenant = new Tenant();
		tenant.setCode("t-" + UUID.randomUUID());
		tenant.setName("Test tenant");
		tenant.setTimeZone("UTC");
		tenant.setCurrency("IDR");
		return tenants.save(tenant).getId();
	}

	/** Stands in for the approval flow (M7), which doesn't exist yet. */
	public static CampaignVersion moveTo(CampaignVersionRepository versions, UUID tenant, UUID versionId,
			CampaignVersionStatus status) {
		return TenantContext.callAs(tenant, () -> {
			CampaignVersion version = versions.findById(versionId).orElseThrow();
			version.setStatus(status);
			version.setApprovedBy("checker");
			version.setApprovedAt(Instant.now());
			return versions.saveAndFlush(version);
		});
	}

	public static void assertRejected(UUID tenant, ScopedValue.CallableOp<?, RuntimeException> op, String code) {
		assertThatThrownBy(() -> TenantContext.callAs(tenant, op))
				.isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo(code));
	}

	/** Runs every caller concurrently; returns the failures (one success expected per test). */
	public static <T> List<Throwable> race(List<Callable<T>> callers) throws InterruptedException {
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
}
