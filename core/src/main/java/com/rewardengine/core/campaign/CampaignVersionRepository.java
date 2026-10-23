package com.rewardengine.core.campaign;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Lookups fetch channels with the version: versions leave the service
 * transaction (open-in-view is off) and channels are always read with them.
 */
public interface CampaignVersionRepository extends JpaRepository<CampaignVersion, UUID> {

	@Override
	@EntityGraph(attributePaths = "channels")
	Optional<CampaignVersion> findById(UUID id);

	@EntityGraph(attributePaths = "channels")
	List<CampaignVersion> findByCampaignIdOrderByVersionNumberAsc(UUID campaignId);

	/** No entity graph: a collection fetch with a row limit would load every version and limit in memory. */
	Optional<CampaignVersion> findFirstByCampaignIdOrderByVersionNumberDesc(UUID campaignId);
}
