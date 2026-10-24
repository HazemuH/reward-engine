package com.rewardengine.core.campaign;

/**
 * Bounds for benefit amounts and counter caps. Far above any real budget, and
 * low enough that a counter's `used + amount` (both at most this) can never
 * overflow bigint at reserve.
 */
public final class RuleAmount {

	public static final long MAX = 1_000_000_000_000_000L;

	public static final String RULE = "a whole number from 1 to " + MAX;

	private RuleAmount() {
	}

	public static boolean isValid(long amount) {
		return amount > 0 && amount <= MAX;
	}
}
