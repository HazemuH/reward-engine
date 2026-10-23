package com.rewardengine.core.campaign;

import java.util.UUID;

/**
 * Version-owned data that lives outside this package (counter limits) and must
 * carry into a newly opened draft. Defined here and implemented by the owning
 * feature so campaign doesn't depend on it; injected as a single bean so a
 * missing implementation fails startup instead of silently opening drafts
 * without their limits. A second implementation means switching CampaignService
 * to a list while keeping that startup check.
 */
public interface VersionCarryOver {

	/** Runs inside the openDraft transaction, after the draft row exists. */
	void carryOver(UUID fromVersionId, UUID draftVersionId);
}
