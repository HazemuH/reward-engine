package com.rewardengine.core.counter;

import com.rewardengine.common.audit.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * A limit as approved in one campaign version: the single source of truth for
 * cap/unit/mode. Raising a limit means a new approved version with a new
 * CounterDef; the matching {@link Counter} rows (keyed by campaign + name)
 * carry their `used` value across versions untouched.
 *
 * sortOrder is the evaluation order — a PARTIAL counter shrinks the amount
 * offered to the counters after it.
 */
@Getter
@Setter
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
}
