package com.rewardengine.core.campaign;

/**
 * See docs/architecture/overview.md section 12 for the full transition
 * diagram (maker/checker approval, kill switch, pause/resume).
 */
public enum CampaignStatus {
	DRAFT,
	PENDING_APPROVAL,
	APPROVED,
	ACTIVE,
	PAUSED,
	ENDED,
	STOPPED
}
