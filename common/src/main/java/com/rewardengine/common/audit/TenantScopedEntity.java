package com.rewardengine.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Base for every tenant-owned table (campaign, counter, redemption, referral, ...).
 *
 * tenantId is mandatory and must be set before persist — see TenantAwareRepository.
 * optimistic lock (@Version) mirrors the "409 on concurrent edit, never silently
 * overwrite" rule from docs/architecture/overview.md section 09.
 */
@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class TenantScopedEntity {

	@Id
	@GeneratedValue
	private UUID id;

	@Column(nullable = false, updatable = false)
	private String tenantId;

	@Version
	private Long version;

	@CreatedDate
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@CreatedBy
	@Column(updatable = false)
	private String createdBy;

	@LastModifiedDate
	private Instant updatedAt;

	@LastModifiedBy
	private String updatedBy;
}
