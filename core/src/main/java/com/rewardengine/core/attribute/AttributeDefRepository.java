package com.rewardengine.core.attribute;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttributeDefRepository extends JpaRepository<AttributeDef, UUID> {

	/** Names are unique regardless of case: "Customer.Segment" next to "customer.segment" invites conditions on the wrong one. */
	// lower(), matching the unique index on (tenant_id, lower(name)); a derived IgnoreCase would use upper()
	@Query("select count(d) > 0 from AttributeDef d where lower(d.name) = lower(:name)")
	boolean existsByNameIgnoreCase(@Param("name") String name);

	List<AttributeDef> findByNameIn(Collection<String> names);

	List<AttributeDef> findBySource(AttributeSource source);

	List<AttributeDef> findAllByOrderByNameAsc();
}
