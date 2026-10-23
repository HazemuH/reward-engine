package com.rewardengine.core.campaign;

/** Repository fragment of {@link CampaignVersionRepository}; implemented by {@link DraftClaimImpl}. */
interface DraftClaim {

	/**
	 * Bumps @Version on a DRAFT version whose version-owned rows (counter limits)
	 * are about to be replaced, so those edits share one optimistic lock with
	 * content edits, and resyncs the loaded entity with the new value.
	 */
	void claimDraft(CampaignVersion version);
}
