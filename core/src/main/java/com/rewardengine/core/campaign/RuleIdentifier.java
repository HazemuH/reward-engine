package com.rewardengine.core.campaign;

import java.util.regex.Pattern;

/**
 * Names a rule script addresses directly — vars, counter names, reference
 * sets — share one shape, so a name that validates is always one a script can
 * write.
 */
public final class RuleIdentifier {

	public static final String RULE = "a letter followed by letters, digits or '_' (at most 100)";

	private static final Pattern PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,99}");

	private RuleIdentifier() {
	}

	public static boolean isValid(String name) {
		return name != null && PATTERN.matcher(name).matches();
	}
}
