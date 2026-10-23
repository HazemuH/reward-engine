package com.rewardengine.core.counter;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CounterDefRepository extends JpaRepository<CounterDef, UUID> {

	List<CounterDef> findByCampaignVersionIdOrderBySortOrderAsc(UUID campaignVersionId);
}
