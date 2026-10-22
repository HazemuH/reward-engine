package com.rewardengine.core.counter;

/**
 * Reset window for a counter. Period keys are computed from server time in the
 * tenant's zone, never from a timestamp the caller sends.
 */
public enum CounterPeriod {
	CAMPAIGN,
	DAY,
	WEEK,
	MONTH
}
