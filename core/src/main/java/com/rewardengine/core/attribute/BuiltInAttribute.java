package com.rewardengine.core.attribute;

import java.util.Arrays;
import java.util.Optional;

/**
 * Attributes the engine supplies itself, valid in every tenant without a
 * catalog entry and reserved so no catalog entry can shadow them.
 */
public enum BuiltInAttribute {
	/** Server time in the tenant's zone — never a time the caller sends. */
	NOW("now", AttributeDataType.DATETIME);

	private final String attributeName;
	private final AttributeDataType dataType;

	BuiltInAttribute(String attributeName, AttributeDataType dataType) {
		this.attributeName = attributeName;
		this.dataType = dataType;
	}

	public String attributeName() {
		return attributeName;
	}

	public AttributeDataType dataType() {
		return dataType;
	}

	public static Optional<BuiltInAttribute> named(String name) {
		return Arrays.stream(values()).filter(b -> b.attributeName.equals(name)).findFirst();
	}

	/**
	 * Case-insensitive, unlike {@link #named}: a catalog attribute "Now" next to
	 * the built-in "now" would let an author condition on a caller-sent time
	 * believing it is server time.
	 */
	public static boolean reserves(String name) {
		return Arrays.stream(values()).anyMatch(b -> b.attributeName.equalsIgnoreCase(name));
	}
}
