package com.rewardengine.core.campaign;

/**
 * Lifecycle of one version. A campaign keeps serving its ACTIVE version while
 * v+1 moves DRAFT → PENDING_APPROVAL → APPROVED; activation flips v+1 to ACTIVE
 * and v to SUPERSEDED. At most one ACTIVE, one PENDING_APPROVAL and one DRAFT
 * version per campaign (partial unique indexes in the migration).
 */
public enum CampaignVersionStatus {
	DRAFT,
	PENDING_APPROVAL,
	APPROVED,
	ACTIVE,
	SUPERSEDED,
	REJECTED
}
