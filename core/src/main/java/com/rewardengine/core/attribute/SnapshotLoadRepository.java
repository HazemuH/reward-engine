package com.rewardengine.core.attribute;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SnapshotLoadRepository extends JpaRepository<SnapshotLoad, UUID> {

	/** At most one per tenant (partial unique index). */
	Optional<SnapshotLoad> findByStatus(SnapshotLoadStatus status);

	/**
	 * FOR SHARE: appends to one load run in parallel, but an activation (which
	 * takes {@link #lockForActivation}) waits until they commit, and an append
	 * arriving after it sees the load is no longer LOADING. The row's FK
	 * KEY SHARE alone wouldn't block the activation's status update.
	 */
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select l from SnapshotLoad l where l.id = :id")
	Optional<SnapshotLoad> lockForAppend(@Param("id") UUID id);

	/** FOR UPDATE: waits out in-flight appends, so the row count taken after it is final. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select l from SnapshotLoad l where l.id = :id")
	Optional<SnapshotLoad> lockForActivation(@Param("id") UUID id);
}
