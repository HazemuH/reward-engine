package com.rewardengine.core.campaign;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * A JPQL bulk UPDATE rather than a dirty entity: nothing on the version row
 * itself changes. The UPDATE holds the row lock the child-table triggers take
 * anyway, so the child writes that follow wait for nothing. JPQL, so @TenantId
 * filters it.
 *
 * Not a @Lock(PESSIMISTIC_FORCE_INCREMENT) finder: on a version already loaded
 * in the persistence context (as editableDraft leaves it) Hibernate takes the
 * row lock but doesn't bump @Version — tried, and the limits-vs-content race
 * tests caught it.
 *
 * It skips the auditing listener: updatedAt is set here, updatedBy must be too
 * once an auditor exists (gateway, M9).
 */
class DraftClaimImpl implements DraftClaim {

	private final EntityManager entityManager;

	DraftClaimImpl(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	@Override
	public void claimDraft(CampaignVersion version) {
		entityManager.flush();
		int claimed = entityManager.createQuery("""
				update CampaignVersion v set v.version = v.version + 1, v.updatedAt = :now
				where v.id = :id and v.version = :version and v.status = :draft""")
				.setParameter("now", Instant.now())
				.setParameter("id", version.getId())
				.setParameter("version", version.getVersion())
				.setParameter("draft", CampaignVersionStatus.DRAFT)
				.executeUpdate();
		// 0 rows: changed since it was read (leaving DRAFT bumps @Version too)
		if (claimed == 0) {
			throw new ObjectOptimisticLockingFailureException(CampaignVersion.class, version.getId());
		}
		entityManager.refresh(version);
	}
}
