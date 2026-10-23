package com.rewardengine.core.campaign;

import com.rewardengine.common.error.BusinessRuleException;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
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
	 * jsonb is stricter than JSON: it refuses the NUL escape, unpaired surrogates
	 * and numbers past its numeric range, all of which the validator's parser
	 * accepts. Those come back as SQL data exceptions (class 22), which are the
	 * input's fault — unlike a lost connection, which still propagates as itself.
	 *
	 * Reads no table, so there is no tenant_id to filter on.
	 */
	String canonicalize(String rawDefinition) {
		try {
			return jdbc.queryForObject("select cast(? as jsonb)::text", String.class, rawDefinition);
		} catch (DataIntegrityViolationException e) {
			String problem = "not storable as jsonb: " + e.getMostSpecificCause().getMessage();
			throw new BusinessRuleException("RULE_DEFINITION_INVALID", "Rule definition is invalid: " + problem,
					List.of(problem));
		}
	}
}
