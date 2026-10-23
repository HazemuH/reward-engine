package com.rewardengine.core.attribute;

/**
 * Postgres refuses NUL in text and jsonb and unpaired surrogates in jsonb.
 * Text headed for the database is checked first, so such input is a validation
 * error naming the field instead of a database error misreported as something
 * else (a duplicate, a concurrent append). Keeping row data out of database
 * errors in general is the datasource's job (logServerErrorDetail=false).
 */
final class StorableText {

	private StorableText() {
	}

	static boolean isStorable(String s) {
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
