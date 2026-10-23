package com.rewardengine.core.campaign;

/**
 * What a rule grants. Discounts are applied at debit, so the quoted amount must
 * be exactly what gets consumed; points and cashback are granted after confirm
 * (architecture overview, "Deferred benefits"). Money amounts are in the
 * tenant's currency, in the same unit as MONEY counters.
 */
public enum BenefitType {
	DISCOUNT_FIXED,
	DISCOUNT_PERCENT,
	POINT,
	CASHBACK;

	public boolean isDiscount() {
		return this == DISCOUNT_FIXED || this == DISCOUNT_PERCENT;
	}
}
