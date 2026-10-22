package com.rewardengine.common.tenant;

import java.util.UUID;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

/**
 * Feeds {@link TenantContext} into Hibernate's {@code @TenantId} filtering.
 *
 * Hibernate opens sessions outside any request too (startup query validation,
 * actuator), so an unbound context can't throw here. It resolves to
 * {@link #NO_TENANT} instead, which no tenant row ever has: tenant-scoped reads
 * come back empty and inserts fail the tenant_id foreign key. Fail closed,
 * never "some other tenant's data".
 */
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

	public static final UUID NO_TENANT = new UUID(0L, 0L);

	@Override
	public UUID resolveCurrentTenantIdentifier() {
		return TenantContext.current().orElse(NO_TENANT);
	}

	@Override
	public boolean validateExistingCurrentSessions() {
		return true;
	}
}
