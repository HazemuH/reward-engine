package com.rewardengine.core.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * One tenant = one deployment customer (one bank, one issuer, ...). Not part
 * of TenantScopedEntity's own hierarchy — this is the row that tenantId
 * elsewhere points *at*. Everything else in core hangs off {@link #id}.
 */
@Getter
@Setter
@Entity
@Table(name = "tenant")
public class Tenant {

	@Id
	@GeneratedValue
	private java.util.UUID id;

	@Column(nullable = false, unique = true)
	private String code;

	@Column(nullable = false)
	private String name;

	/** IANA zone used for counter periods (DAY/WEEK/MONTH) — see application.yml reward.period-zone default. */
	@Column(nullable = false)
	private String timeZone;

	@Column(nullable = false)
	private boolean active = true;

	@Column(nullable = false, updatable = false)
	private Instant createdAt = Instant.now();
}
