package com.rewardengine.core.attribute;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttributeSnapshotRepository extends JpaRepository<AttributeSnapshot, UUID> {

	/** The row in whichever load is ACTIVE, in one indexed query (typing it needs the catalog too). */
	@Query("""
			select new com.rewardengine.core.attribute.ActiveSnapshot(s, l.activatedAt)
			from AttributeSnapshot s, SnapshotLoad l
			where s.loadId = l.id and l.status = com.rewardengine.core.attribute.SnapshotLoadStatus.ACTIVE
			and s.userKey = :userKey""")
	Optional<ActiveSnapshot> findActive(@Param("userKey") String userKey);

	long countByLoadId(UUID loadId);

	@Query("select s.userKey from AttributeSnapshot s where s.loadId = :loadId and s.userKey in :userKeys")
	List<String> findUserKeysInLoad(@Param("loadId") UUID loadId, @Param("userKeys") Collection<String> userKeys);
}
