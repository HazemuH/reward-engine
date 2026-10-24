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

	/** Characters as varchar(n) counts them: code points, not UTF-16 units. */
	public static int length(String s) {
		return s.codePointCount(0, s.length());
	}

	/** Storable and at most {@code maxLength} characters — the usual check for a bounded text column. */
	public static boolean fits(String s, int maxLength) {
		return length(s) <= maxLength && isStorable(s);
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
