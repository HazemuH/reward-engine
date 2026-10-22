package com.rewardengine.core.counter;

import com.rewardengine.common.audit.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * The only table `used` is allowed to move on — and only through the atomic
 * UPDATE statements in the redemption service (reserve/confirm/cancel), never
 * a read-then-write from application code or a script. See
 * docs/architecture/overview.md section 09 for the exact SQL shape and
 * .claude/skills/money-safety-check before touching this class or its
 * repository.
 */
@Getter
@Setter
@Entity
@Table(
		name = "counter",
		uniqueConstraints = @UniqueConstraint(columnNames = {"campaign_id", "name", "scope_key", "period_key"}))
public class Counter extends TenantScopedEntity {

	@Column(name = "campaign_id", nullable = false)
	private UUID campaignId;

	@Column(nullable = false)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private CounterScope scope;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private CounterUnit unit;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private CounterMode mode;

	/** "" for CAMPAIGN scope, userKey for USER scope, merchantId for MERCHANT scope. */
	@Column(name = "scope_key", nullable = false)
	private String scopeKey = "";

	/** "" for no period (whole-campaign cap), otherwise e.g. "2026-10" for MONTH. */
	@Column(name = "period_key", nullable = false)
	private String periodKey = "";

	@Column(nullable = false)
	private long cap;

	@Column(nullable = false)
	private long used = 0;
}
