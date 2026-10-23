package com.rewardengine.core.counter;

import com.rewardengine.common.audit.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A limit as approved in one campaign version: the single source of truth for
 * cap/unit/mode. Raising a limit means a new approved version with a new
 * CounterDef; the matching {@link Counter} rows (keyed by campaign + name)
 * carry their `used` value across versions untouched.
 *
 * sortOrder is the evaluation order — a PARTIAL counter shrinks the amount
 * offered to the counters after it.
 *
 * No setters: a draft's limits change only by being replaced as a whole through
 * {@link CounterDefService}, so a loaded row can't be edited in place.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "counter_def")
public class CounterDef extends TenantScopedEntity {

	@Column(nullable = false, updatable = false)
	private UUID campaignVersionId;

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
	private CounterPeriod period;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private CounterMode mode;

	@Column(nullable = false)
	private long cap;

	@Column(nullable = false)
	private int sortOrder;

	/** Not validated here: CounterDefService checks a whole list before defining any of it. */
	public static CounterDef define(UUID campaignVersionId, CounterLimit limit, int sortOrder) {
		CounterDef def = new CounterDef();
		def.campaignVersionId = campaignVersionId;
		def.name = limit.name();
		def.scope = limit.scope();
		def.unit = limit.unit();
		def.period = limit.period();
		def.mode = limit.mode();
		def.cap = limit.cap();
		def.sortOrder = sortOrder;
		return def;
	}

	/** Same limit, owned by another (draft) version — how a new version starts from the previous one. */
	public CounterDef copyFor(UUID targetVersionId) {
		return define(targetVersionId, CounterLimit.of(this), sortOrder);
	}
}
