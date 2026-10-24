package com.rewardengine.core.attribute;

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

	/**
	 * Names that would nest with {@code name} in either direction (customer vs
	 * customer.segment): one would be both a value and a namespace in script facts.
	 * substring, not LIKE: '_' in a name would be a wildcard.
	 */
	@Query("""
			select d.name from AttributeDef d
			where substring(lower(d.name), 1, length(:name) + 1) = concat(lower(:name), '.')
			or substring(lower(:name), 1, length(d.name) + 1) = concat(lower(d.name), '.')""")
	List<String> findNamesNestingWith(@Param("name") String name);

	List<AttributeDef> findBySource(AttributeSource source);

	List<AttributeDef> findAllByOrderByNameAsc();

}
