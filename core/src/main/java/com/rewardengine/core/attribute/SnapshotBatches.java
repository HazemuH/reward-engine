package com.rewardengine.core.attribute;

import com.rewardengine.common.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The record of each batch appended to a load, keyed by the loader's batchKey
 * with a hash of what it carried — the idempotency record for append, like
 * idempotency_key is for the transaction endpoints. JDBC because recording
 * needs ON CONFLICT DO NOTHING: a retry overlapping its in-flight original
 * waits on the unique key, then finds the committed record instead of failing.
 * Native SQL skips @TenantId, so tenant_id is bound here from TenantContext.
 */
@Component
class SnapshotBatches {

	private final JdbcTemplate jdbc;

	SnapshotBatches(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** True if this call recorded the batch; false if it was recorded before (by now committed). */
	boolean record(UUID loadId, String batchKey, String requestHash, int rows) {
		return jdbc.update("""
				insert into snapshot_batch (tenant_id, load_id, batch_key, request_hash, row_count)
				values (?, ?, ?, ?, ?)
				on conflict (load_id, batch_key) do nothing""",
				TenantContext.get(), loadId, batchKey, requestHash, rows) == 1;
	}

	Optional<String> requestHash(UUID loadId, String batchKey) {
		List<String> hashes = jdbc.queryForList(
				"select request_hash from snapshot_batch where tenant_id = ? and load_id = ? and batch_key = ?",
				String.class, TenantContext.get(), loadId, batchKey);
		return hashes.stream().findFirst();
	}
}
