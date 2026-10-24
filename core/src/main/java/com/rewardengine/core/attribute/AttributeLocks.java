package com.rewardengine.core.attribute;

import com.rewardengine.common.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A transaction-scoped advisory lock for the invariant here that no constraint
 * can hold: one catalog change at a time per tenant (names may not nest).
 * Narrow on purpose: it locks no row, so nothing else that touches the tenant
 * contends with it, and tenants never wait on each other.
 *
 * Relies on READ COMMITTED (the default here): reads after the lock must see
 * what the define it waited for committed. Under REPEATABLE READ the snapshot
 * would predate the wait.
 *
 * Keys are (namespace, hash): a hash collision only serializes two unrelated
 * callers, it never lets two conflicting ones through. Released at commit or
 * rollback. Reads no table, so there is no tenant_id to filter; the catalog key
 * takes the tenant from TenantContext, never from a caller.
 */
@Component
class AttributeLocks {

	private static final int CATALOG = 0x41_43_41_54;

	private final JdbcTemplate jdbc;

	AttributeLocks(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	void lockCatalog() {
		lock(CATALOG, TenantContext.get().toString());
	}

	private void lock(int namespace, String key) {
		jdbc.queryForObject("select 1 from (select pg_advisory_xact_lock(?, hashtext(?))) l", Integer.class,
				namespace, key);
	}
}
