package com.rewardengine.core.campaign;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignRepository extends JpaRepository<Campaign, UUID> {

	/**
	 * Row lock on the campaign header: serializes new-version creation so two
	 * callers can't both pick the same next version number or both open a draft.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Campaign c where c.id = :id")
	Optional<Campaign> findForUpdate(@Param("id") UUID id);
}
