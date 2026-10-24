package com.rewardengine.core.campaign;

import com.rewardengine.common.error.BusinessRuleException;
import java.util.List;
import java.sql.SQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Rewrites a rule definition into the exact text Postgres returns for it from a
 * jsonb column. Hibernate dirty-checks rawDefinition as a string, so an
 * unchanged definition resent with other spacing or key order would otherwise
 * bump @Version and hand every other editor a false 409.
 *
 * Asks Postgres rather than reimplementing jsonb's output in Java: its key order
 * (by length, then bytes), spacing and number formatting are its own, and only
 * its answer is guaranteed to match what a later read hands back.
 */
@Component
class RuleDefinitionCanonicalizer {

	private final JdbcTemplate jdbc;

	RuleDefinitionCanonicalizer(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * The validator already refuses text jsonb can't hold; this is the backstop
	 * for anything it misses. Only an SQL data exception (state class 22) is the
	 * input's fault — any other failure propagates as itself — and the database's
	 * message, which quotes the input, is not passed on.
	 *
	 * Reads no table, so there is no tenant_id to filter on.
	 */
	String canonicalize(String rawDefinition) {
		try {
			return jdbc.queryForObject("select cast(? as jsonb)::text", String.class, rawDefinition);
		} catch (DataAccessException e) {
			if (!(e.getMostSpecificCause() instanceof SQLException sql)
					|| sql.getSQLState() == null || !sql.getSQLState().startsWith("22")) {
				throw e;
			}
			String problem = "not storable as jsonb";
			throw new BusinessRuleException("RULE_DEFINITION_INVALID", "Rule definition is invalid: " + problem,
					List.of(problem));
		}
	}
}
