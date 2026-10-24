package com.rewardengine.core.counter;

import com.rewardengine.common.error.BusinessRuleException;
import com.rewardengine.core.campaign.CampaignService;
import com.rewardengine.core.campaign.CampaignVersion;
import com.rewardengine.core.campaign.RuleAmount;
import com.rewardengine.core.campaign.RuleIdentifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The authoring path for a draft version's counter limits. Limits are version
 * content, so they follow the version's rules: editable only while DRAFT (the
 * DB triggers back this up), and guarded by the version's own @Version — a
 * limits edit and a content edit made from the same stale read can't both win.
 *
 * Only per-limit shape is checked here. Whether the set fits the version's
 * benefits (a budget counter present, discounts only on ALL_OR_NOTHING) is
 * decided at submit, when benefits and limits are final together.
 */
@Service
@Transactional(readOnly = true)
public class CounterDefService {

	private final CounterDefRepository counterDefs;
	private final CampaignService campaigns;

	public CounterDefService(CounterDefRepository counterDefs, CampaignService campaigns) {
		this.counterDefs = counterDefs;
		this.campaigns = campaigns;
	}

	/** In evaluation order. */
	public List<CounterDef> limits(UUID versionId) {
		campaigns.getVersion(versionId);
		return counterDefs.findByCampaignVersionIdOrderBySortOrderAsc(versionId);
	}

	/**
	 * Replaces the draft's limits with {@code limits}, in that order, and returns
	 * the version with its new @Version for the caller's next edit. An unchanged
	 * list writes nothing and keeps @Version, like an unchanged content save.
	 */
	@Transactional
	public CampaignVersion replaceDraftLimits(UUID versionId, long expectedVersion, List<CounterLimit> limits) {
		CampaignVersion version = campaigns.editableDraft(versionId, expectedVersion);
		validate(limits);
		List<CounterDef> current = counterDefs.findByCampaignVersionIdOrderBySortOrderAsc(versionId);
		if (current.stream().map(CounterLimit::of).toList().equals(limits)) {
			return version;
		}
		campaigns.claimDraft(version);
		// Deletes flushed before the inserts: one flush would insert first and trip
		// the unique name and sort order of every limit that stays.
		counterDefs.deleteAll(current);
		counterDefs.flush();
		counterDefs.saveAllAndFlush(IntStream.range(0, limits.size())
				.mapToObj(i -> CounterDef.define(versionId, limits.get(i), i))
				.toList());
		return version;
	}

	private static void validate(List<CounterLimit> limits) {
		List<String> violations = new ArrayList<>();
		Set<String> names = new HashSet<>();
		for (int i = 0; i < limits.size(); i++) {
			CounterLimit limit = limits.get(i);
			String path = "counters[" + i + "]";
			if (limit == null) {
				violations.add(path + ": must not be null");
				continue;
			}
			// scripts consume counters by name
			if (!RuleIdentifier.isValid(limit.name())) {
				violations.add(path + ".name: " + RuleIdentifier.RULE);
			} else if (!names.add(limit.name())) {
				violations.add(path + ".name: \"" + limit.name() + "\" is used by another counter");
			}
			if (limit.scope() == null) {
				violations.add(path + ".scope: required");
			}
			if (limit.unit() == null) {
				violations.add(path + ".unit: required");
			}
			if (limit.period() == null) {
				violations.add(path + ".period: required");
			}
			if (limit.mode() == null) {
				violations.add(path + ".mode: required");
			} else if (limit.mode() == CounterMode.PARTIAL && limit.unit() == CounterUnit.COUNT) {
				// a redemption consumes exactly 1 of a COUNT counter; there is no partial share of it
				violations.add(path + ".mode: PARTIAL needs a MONEY or POINT unit");
			}
			// zero would reject every reserve (a typo, not a closed counter); the upper bound
			// keeps `used + amount` inside bigint
			if (!RuleAmount.isValid(limit.cap())) {
				violations.add(path + ".cap: " + RuleAmount.RULE);
			}
		}
		if (!violations.isEmpty()) {
			throw new BusinessRuleException("COUNTER_LIMITS_INVALID",
					"Counter limits are invalid: " + String.join("; ", violations), violations);
		}
	}
}
