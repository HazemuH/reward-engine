package com.rewardengine.core.campaign;

import com.rewardengine.common.error.BusinessRuleException;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Campaign headers and their DRAFT versions. Moving a version past DRAFT
 * (submit, approve, activate) belongs to the approval flow, not here.
 *
 * Edits take the version the caller last read: a mismatch is an
 * {@link ObjectOptimisticLockingFailureException} (409 at the web layer), same
 * as a concurrent write caught by @Version at flush.
 */
@Service
@Transactional(readOnly = true)
public class CampaignService {

	private final CampaignRepository campaigns;
	private final CampaignVersionRepository versions;

	public CampaignService(CampaignRepository campaigns, CampaignVersionRepository versions) {
		this.campaigns = campaigns;
		this.versions = versions;
	}

	/** New campaign in DRAFT with its first version (v1) as an open draft. */
	@Transactional
	public CampaignVersion create(String name, DraftContent content) {
		Campaign campaign = new Campaign();
		campaign.setName(requireName(name));
		UUID campaignId = campaigns.save(campaign).getId();
		return versions.saveAndFlush(newVersion(campaignId, 1, content));
	}

	public Page<Campaign> list(Pageable pageable) {
		return campaigns.findAll(pageable);
	}

	public Campaign get(UUID campaignId) {
		return campaigns.findById(campaignId).orElseThrow(() -> campaignNotFound(campaignId));
	}

	public List<CampaignVersion> versions(UUID campaignId) {
		if (!campaigns.existsById(campaignId)) {
			throw campaignNotFound(campaignId);
		}
		return versions.findByCampaignIdOrderByVersionNumberAsc(campaignId);
	}

	public CampaignVersion getVersion(UUID versionId) {
		return versions.findById(versionId).orElseThrow(() -> new BusinessRuleException(
				"VERSION_NOT_FOUND", "Campaign version " + versionId + " not found"));
	}

	/** The name is an internal label, not rule content, so it stays editable in any status. */
	@Transactional
	public Campaign rename(UUID campaignId, long expectedVersion, String name) {
		Campaign campaign = get(campaignId);
		checkVersion(Campaign.class, campaignId, expectedVersion, campaign.getVersion());
		campaign.setName(requireName(name));
		return campaigns.saveAndFlush(campaign);
	}

	/**
	 * Opens v+1 as a DRAFT, starting from the latest version's content whatever
	 * its status — after a rejection that's the content being revised, and the
	 * approver reviews the new version in full anyway. One open draft per
	 * campaign; the partial unique index backs this up if the check is ever bypassed.
	 */
	@Transactional
	public CampaignVersion openDraft(UUID campaignId) {
		campaigns.findForUpdate(campaignId).orElseThrow(() -> campaignNotFound(campaignId));
		if (versions.existsByCampaignIdAndStatus(campaignId, CampaignVersionStatus.DRAFT)) {
			throw new BusinessRuleException("DRAFT_ALREADY_OPEN",
					"Campaign " + campaignId + " already has an open draft version");
		}
		CampaignVersion latest = versions.findFirstByCampaignIdOrderByVersionNumberDesc(campaignId).orElseThrow();
		return versions.saveAndFlush(
				newVersion(campaignId, latest.getVersionNumber() + 1, DraftContent.copyOf(latest)));
	}

	@Transactional
	public CampaignVersion editDraft(UUID versionId, long expectedVersion, DraftContent content) {
		CampaignVersion version = getVersion(versionId);
		checkVersion(CampaignVersion.class, versionId, expectedVersion, version.getVersion());
		version.editDraft(content);
		return versions.saveAndFlush(version);
	}

	private static CampaignVersion newVersion(UUID campaignId, int versionNumber, DraftContent content) {
		CampaignVersion version = new CampaignVersion();
		version.setCampaignId(campaignId);
		version.setVersionNumber(versionNumber);
		version.editDraft(content);
		return version;
	}

	private static void checkVersion(Class<?> type, UUID id, long expected, long actual) {
		if (expected != actual) {
			throw new ObjectOptimisticLockingFailureException(type, id);
		}
	}

	private static String requireName(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Campaign name must not be blank");
		}
		return name.strip();
	}

	private static BusinessRuleException campaignNotFound(UUID campaignId) {
		return new BusinessRuleException("CAMPAIGN_NOT_FOUND", "Campaign " + campaignId + " not found");
	}
}
