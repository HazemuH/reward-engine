package com.rewardengine.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.TenantId;

/**
 * Base for every tenant-owned table (campaign, counter, redemption, referral, ...).
 *
 * {@code @TenantId} makes Hibernate stamp tenantId on insert from
 * {@link com.rewardengine.common.tenant.TenantIdentifierResolver} and add
 * {@code tenant_id = ?} to every query on these entities. There is no setter on
 * purpose: application code can't pick a tenant, only run inside one
 * ({@link com.rewardengine.common.tenant.TenantContext#callAs}).
 */
@Getter
@MappedSuperclass
public abstract class TenantScopedEntity extends BaseEntity {

	@TenantId
	@Column(nullable = false, updatable = false)
	private UUID tenantId;
}
