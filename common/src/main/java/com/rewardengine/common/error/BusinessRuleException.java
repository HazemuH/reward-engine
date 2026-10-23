package com.rewardengine.common.error;

import java.util.List;

/**
 * Thrown for any rejection that has a business error code (QUOTA_EXHAUSTED,
 * QUOTE_EXPIRED, ...). The code is kept separate from the HTTP status: the web
 * layer maps it, callers switch on the code, not the status text.
 *
 * details lists every individual problem when one rejection covers several
 * (e.g. each schema violation in a rule definition), so an editor can fix them
 * all in one pass instead of one per round-trip.
 *
 * The code table lives in docs/architecture/overview.md ("Business error
 * codes") — keep tenant-specific code tables out of this class; this stays generic.
 */
public class BusinessRuleException extends RuntimeException {

	private final String code;
	private final List<String> details;

	public BusinessRuleException(String code, String message) {
		this(code, message, List.of());
	}

	public BusinessRuleException(String code, String message, List<String> details) {
		super(message);
		this.code = code;
		this.details = List.copyOf(details);
	}

	public String getCode() {
		return code;
	}

	public List<String> getDetails() {
		return details;
	}
}
