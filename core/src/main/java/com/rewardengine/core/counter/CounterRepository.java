package com.rewardengine.core.counter;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CounterRepository extends JpaRepository<Counter, UUID> {

	/**
	 * The ALL_OR_NOTHING consumption statement from
	 * docs/architecture/overview.md section 09 — one UPDATE, no prior read.
	 * Returns 0 rows updated when the cap would be exceeded; callers must
	 * treat that as a rejection (QUOTA_EXHAUSTED), not retry it as a transient error.
	 */
	@Modifying
	@Query("""
			update Counter c set c.used = c.used + :amount
			where c.id = :counterId and c.used + :amount <= c.cap
			""")
	int consumeAllOrNothing(@Param("counterId") UUID counterId, @Param("amount") long amount);

	// PARTIAL consumption needs SELECT ... FOR UPDATE followed by a bounded
	// UPDATE in the same transaction — deliberately not a derived query;
	// see the service layer once redemption/reserve is implemented, and
	// .claude/skills/money-safety-check before adding it.
}
