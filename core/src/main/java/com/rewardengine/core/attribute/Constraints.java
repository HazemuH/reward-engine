package com.rewardengine.core.attribute;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Translating a unique violation into a business answer only when it is the
 * constraint the code expects; anything else is a real fault and propagates.
 */
final class Constraints {

	static final String ATTRIBUTE_NAME = "uq_attribute_def_name";
	static final String SNAPSHOT_USER = "uq_attribute_snapshot_user";
	static final String ACTIVE_LOAD = "uq_snapshot_load_active";

	private Constraints() {
	}

	static boolean violated(DataIntegrityViolationException e, String constraint) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof ConstraintViolationException cve) {
				return constraint.equalsIgnoreCase(cve.getConstraintName());
			}
		}
		return false;
	}
}
