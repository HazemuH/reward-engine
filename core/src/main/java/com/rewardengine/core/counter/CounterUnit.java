package com.rewardengine.core.counter;

/**
 * COUNT: each redemption consumes 1. MONEY: total benefit value, in the
 * tenant's currency (Tenant#currency). POINT: points granted.
 */
public enum CounterUnit {
	COUNT,
	MONEY,
	POINT
}
