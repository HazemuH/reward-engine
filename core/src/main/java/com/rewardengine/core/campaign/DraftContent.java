package com.rewardengine.core.campaign;

import java.util.Objects;
import java.util.Set;

/**
 * The editable part of a {@link CampaignVersion}. rawDefinition is stored as-is
 * here; validating it against the rule schema is RE-011's job.
 */
public record DraftContent(String rawDefinition, String optionalScript, Set<String> channels) {

	public DraftContent {
		Objects.requireNonNull(rawDefinition, "rawDefinition");
		channels = channels == null ? Set.of() : Set.copyOf(channels);
	}

	static DraftContent copyOf(CampaignVersion version) {
		return new DraftContent(version.getRawDefinition(), version.getOptionalScript(), version.getChannels());
	}
}
