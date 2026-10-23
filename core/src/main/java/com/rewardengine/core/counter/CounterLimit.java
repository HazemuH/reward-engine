package com.rewardengine.core.counter;

/**
 * One limit as authored on a draft version. Its position in the list passed to
 * {@link CounterDefService#replaceDraftLimits} becomes {@link CounterDef#getSortOrder()}.
 */
public record CounterLimit(String name, CounterScope scope, CounterUnit unit, CounterPeriod period,
		CounterMode mode, long cap) {

	static CounterLimit of(CounterDef def) {
		return new CounterLimit(def.getName(), def.getScope(), def.getUnit(), def.getPeriod(), def.getMode(),
				def.getCap());
	}
}
