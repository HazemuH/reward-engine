package com.rewardengine.core.attribute;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttributeDefRepository extends JpaRepository<AttributeDef, UUID> {

	Optional<AttributeDef> findByName(String name);

	List<AttributeDef> findByNameIn(Collection<String> names);

	List<AttributeDef> findBySource(AttributeSource source);

	List<AttributeDef> findAllByOrderByNameAsc();
}
