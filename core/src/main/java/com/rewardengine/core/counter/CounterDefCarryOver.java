package com.rewardengine.core.counter;

import com.rewardengine.core.campaign.VersionCarryOver;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** A new draft starts with the previous version's caps, never with none. */
@Component
class CounterDefCarryOver implements VersionCarryOver {

	private final CounterDefRepository counterDefs;

	CounterDefCarryOver(CounterDefRepository counterDefs) {
		this.counterDefs = counterDefs;
	}

	// MANDATORY: copying limits outside the draft's own transaction could leave a draft without them
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void carryOver(UUID fromVersionId, UUID draftVersionId) {
		counterDefs.saveAll(counterDefs.findByCampaignVersionIdOrderBySortOrderAsc(fromVersionId).stream()
				.map(def -> def.copyFor(draftVersionId))
				.toList());
	}
}
