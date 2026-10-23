package com.rewardengine.core.attribute;

import java.util.Map;

/**
 * One customer's attributes as handed over by a loader. A null or blank value
 * means the attribute is absent for this customer, which makes any condition on
 * it false.
 *
 * toString names the attributes but never their values: a loader logging a
 * rejected row must not write a sensitive value into the logs.
 */
public record SnapshotRow(String userKey, Map<String, Object> values) {

	public SnapshotRow {
		values = values == null ? Map.of() : values;
	}

	@Override
	public String toString() {
		return "SnapshotRow[attributes=" + values.keySet() + "]";
	}
}
