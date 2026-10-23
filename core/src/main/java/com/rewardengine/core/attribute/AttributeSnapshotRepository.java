package com.rewardengine.core.attribute;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttributeSnapshotRepository extends JpaRepository<AttributeSnapshot, UUID> {

	/** One indexed lookup on the calculate path: the row in whichever load is ACTIVE. */
	@Query("""
			select new com.rewardengine.core.attribute.ActiveSnapshot(s, l.activatedAt)
			from AttributeSnapshot s, SnapshotLoad l
			where s.loadId = l.id and l.status = com.rewardengine.core.attribute.SnapshotLoadStatus.ACTIVE
			and s.userKey = :userKey""")
	Optional<ActiveSnapshot> findActive(@Param("userKey") String userKey);

	@Query("select s.userKey from AttributeSnapshot s where s.loadId = :loadId and s.userKey in :userKeys")
	List<String> findUserKeysInLoad(@Param("loadId") UUID loadId, @Param("userKeys") Collection<String> userKeys);
}
