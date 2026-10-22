package com.rewardengine.core.counter;

import com.rewardengine.common.audit.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * Usage of one limit, per (campaign, name, scope key, period key). The cap is
 * not stored here — it comes from the {@link CounterDef} of the version being
 * redeemed against.
 *
 * `used` is read-only to JPA (insertable/updatable = false, no setter): the
 * only writer is the conditional UPDATE in {@link CounterRepository}, which
 * also bumps @Version. A stale Counter entity saved later therefore fails its
 * version check instead of writing an old `used` back.
 * Read .claude/skills/money-safety-check before changing any of this.
 */
@Getter
@Setter
@Entity
@Table(name = "counter")
public class Counter extends TenantScopedEntity {

	@Column(nullable = false, updatable = false)
	private UUID campaignId;

	@Column(nullable = false, updatable = false)
	private String name;

	/** "" for CAMPAIGN scope, userKey for USER scope, merchantId for MERCHANT scope. */
	@Column(nullable = false, updatable = false)
	private String scopeKey = "";

	/** "" for a whole-campaign period, otherwise e.g. "2026-10" for MONTH. */
	@Column(nullable = false, updatable = false)
	private String periodKey = "";

	@Setter(AccessLevel.NONE)
	@Column(nullable = false, insertable = false, updatable = false)
	private long used;
}
