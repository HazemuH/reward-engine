package com.rewardengine.core.attribute;

import java.util.regex.Pattern;

/** Dotted attribute names such as {@code customer.segment}, as conditions and the catalog write them. */
public final class AttributeName {

	public static final String RULE = "a dotted attribute name such as \"customer.segment\" (at most 100 characters)";

	private static final Pattern PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*");
	private static final int MAX_LENGTH = 100;

	private AttributeName() {
	}

	public static boolean isValid(String name) {
		return name != null && name.length() <= MAX_LENGTH && PATTERN.matcher(name).matches();
	}
}
