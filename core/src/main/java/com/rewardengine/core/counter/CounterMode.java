package com.rewardengine.core.counter;

/**
 * ALL_OR_NOTHING: used for discounts — the amount shown in the quote must
 * exactly match what gets debited, so it either fully succeeds or is
 * rejected whole (QUOTA_EXHAUSTED).
 * PARTIAL: only for deferred benefits (points/cashback) — takes whatever is
 * left, down to zero. Counter order matters when several PARTIAL counters
 * chain (see docs/architecture/overview.md section 03).
 */
public enum CounterMode {
	ALL_OR_NOTHING,
	PARTIAL
}
