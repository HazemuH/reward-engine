package com.rewardengine.core.campaign;

import java.util.Objects;
import java.util.Set;

/**
 * The editable part of a {@link CampaignVersion}. CampaignService validates
 * rawDefinition against the rule schema and canonicalizes it before it reaches
 * the entity.
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
