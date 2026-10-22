package com.rewardengine.core.redemption;

/**
 * See docs/architecture/overview.md section 08 for the full diagram.
 * PENDING is the status most often mishandled downstream: the counter stays
 * consumed until the core banking result is known — never release it just
 * because a response was slow.
 */
public enum RedemptionStatus {
	QUOTED,
	RESERVED,
	USED,
	PENDING,
	RELEASED,
	REVERSED
}
