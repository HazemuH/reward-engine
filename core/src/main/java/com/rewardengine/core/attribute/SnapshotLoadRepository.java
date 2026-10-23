package com.rewardengine.core.attribute;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SnapshotLoadRepository extends JpaRepository<SnapshotLoad, UUID> {

	/** At most one per tenant (partial unique index). */
	Optional<SnapshotLoad> findByStatus(SnapshotLoadStatus status);
}
