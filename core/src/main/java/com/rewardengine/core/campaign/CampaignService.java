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

	private static final int MAX_NAME_LENGTH = 200;

	private final CampaignRepository campaigns;
	private final CampaignVersionRepository versions;
	private final VersionCarryOver carryOver;

	public CampaignService(CampaignRepository campaigns, CampaignVersionRepository versions,
			VersionCarryOver carryOver) {
		this.campaigns = campaigns;
		this.versions = versions;
		this.carryOver = carryOver;
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

	/** Every campaign has at least v1, so an empty result means no such campaign for this tenant. */
	public List<CampaignVersion> versions(UUID campaignId) {
		List<CampaignVersion> found = versions.findByCampaignIdOrderByVersionNumberAsc(campaignId);
		if (found.isEmpty()) {
			throw campaignNotFound(campaignId);
		}
		return found;
	}

	public CampaignVersion getVersion(UUID versionId) {
		return versions.findById(versionId).orElseThrow(() -> versionNotFound(versionId));
	}

	/** The name is an internal label, not rule content, so it stays editable in any status. */
	@Transactional
	public Campaign rename(UUID campaignId, long expectedVersion, String name) {
		String validName = requireName(name);
		Campaign campaign = get(campaignId);
		checkVersion(Campaign.class, campaignId, expectedVersion, campaign.getVersion());
		campaign.setName(validName);
		return campaigns.saveAndFlush(campaign);
	}

	/**
	 * Opens v+1 as a DRAFT, starting from the latest version's content and
	 * counter limits whatever its status — after a rejection that's the content
	 * being revised, and the approver reviews the new version in full anyway.
	 *
	 * One open draft per campaign. Only this method creates v2+, and it refuses
	 * while a draft is open, so an open draft is always the latest version; the
	 * partial unique index backs this up if that ever stops being true.
	 */
	@Transactional
	public CampaignVersion openDraft(UUID campaignId) {
		campaigns.lockForNewVersion(campaignId).orElseThrow(() -> campaignNotFound(campaignId));
		CampaignVersion latest = versions.findFirstByCampaignIdOrderByVersionNumberDesc(campaignId).orElseThrow();
		if (latest.getStatus() == CampaignVersionStatus.DRAFT) {
			throw new BusinessRuleException("DRAFT_ALREADY_OPEN",
					"Campaign " + campaignId + " already has an open draft version");
		}
		CampaignVersion draft = versions.saveAndFlush(
				newVersion(campaignId, latest.getVersionNumber() + 1, DraftContent.copyOf(latest)));
		carryOver.carryOver(latest.getId(), draft.getId());
		return draft;
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
		String stripped = name == null ? "" : name.strip();
		// code points, not UTF-16 units: varchar(200) counts characters
		if (stripped.isEmpty() || stripped.codePointCount(0, stripped.length()) > MAX_NAME_LENGTH) {
			throw new BusinessRuleException("CAMPAIGN_NAME_INVALID",
					"Campaign name must be 1-" + MAX_NAME_LENGTH + " characters");
		}
		return stripped;
	}

	private static BusinessRuleException campaignNotFound(UUID campaignId) {
		return new BusinessRuleException("CAMPAIGN_NOT_FOUND", "Campaign " + campaignId + " not found");
	}

	private static BusinessRuleException versionNotFound(UUID versionId) {
		return new BusinessRuleException("VERSION_NOT_FOUND", "Campaign version " + versionId + " not found");
	}
}
