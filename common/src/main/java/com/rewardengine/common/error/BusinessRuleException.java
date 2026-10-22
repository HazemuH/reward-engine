package com.rewardengine.common.error;

/**
 * Thrown for any rejection that has a business error code (QUOTA_EXHAUSTED, QUOTE_EXPIRED, ...).
 * The code is kept separate from the HTTP status: the web layer maps it,
 * callers (the caller) switch on the code, not the status text.
 *
 * See docs/architecture/overview.md section 15 for the code table — keep
 * tenant-specific code tables out of this class; this stays generic.
 */
public class BusinessRuleException extends RuntimeException {

	private final String code;

	public BusinessRuleException(String code, String message) {
		super(message);
		this.code = code;
	}

	public String getCode() {
		return code;
	}
}
