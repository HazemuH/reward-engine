package com.rewardengine.core.campaign;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CampaignRepository extends JpaRepository<Campaign, UUID> {

	/**
	 * Row lock on the campaign header: serializes new-version creation so two
	 * callers can't both pick the same next version number or both open a draft.
	 *
	 * Native because JPA's PESSIMISTIC_WRITE is FOR UPDATE on Postgres, which also
	 * blocks the FK key-share locks taken by counter/redemption inserts on the
	 * reserve path; FOR NO KEY UPDATE doesn't. Native SQL skips @TenantId, hence
	 * the explicit tenant_id.
	 */
	@Query(value = "select id from campaign where id = :id and tenant_id = :tenantId for no key update",
			nativeQuery = true)
	Optional<UUID> lockForNewVersion(@Param("id") UUID id, @Param("tenantId") UUID tenantId);
}
