package com.rewardengine.common.text;

/**
 * Postgres refuses NUL in text and jsonb and unpaired surrogates in jsonb, and
 * the driver silently turns an unpaired surrogate into '?' when it encodes a
 * parameter, so such text is either rejected by the database (a raw error,
 * easily misreported) or stored altered. Text headed for the database is
 * checked first and refused as a validation error naming the field.
 */
public final class StorableText {

	private StorableText() {
	}

	public static boolean isStorable(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == 0) {
				return false;
			}
			if (Character.isHighSurrogate(c)) {
				if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
					return false;
				}
				i++;
			} else if (Character.isLowSurrogate(c)) {
				return false;
			}
		}
		return true;
	}
}
