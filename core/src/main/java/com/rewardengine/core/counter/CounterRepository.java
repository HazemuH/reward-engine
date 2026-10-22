package com.rewardengine.core.counter;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CounterRepository extends JpaRepository<Counter, UUID> {

	/**
	 * ALL_OR_NOTHING consumption: one conditional UPDATE, no prior read.
	 *
	 * Returns 0 when the cap would be exceeded, when the campaign isn't ACTIVE
	 * (kill switch / pause take effect here even if a rule cache is stale), or
	 * when the counter belongs to another tenant (@TenantId restriction).
	 * Callers treat 0 as a rejection (QUOTA_EXHAUSTED), never as a transient error to retry.
	 *
	 * cap comes from the CounterDef of the version being redeemed against.
	 * PARTIAL consumption (SELECT ... FOR UPDATE, then a bounded UPDATE) is
	 * deliberately not here yet — see money-safety-check before adding it.
	 */
	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update Counter c set c.used = c.used + :amount, c.version = c.version + 1
			where c.id = :counterId
			  and c.used + :amount <= :cap
			  and exists (select 1 from Campaign k
			              where k.id = c.campaignId
			                and k.status = com.rewardengine.core.campaign.CampaignStatus.ACTIVE)
			""")
	int consumeAllOrNothing(@Param("counterId") UUID counterId, @Param("amount") long amount, @Param("cap") long cap);
}
