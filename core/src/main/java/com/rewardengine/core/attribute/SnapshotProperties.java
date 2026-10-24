package com.rewardengine.core.attribute;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * reward.snapshot.*: limits a deployment may tune. A missing or non-positive
 * value fails startup rather than silently allowing nothing (or everything).
 *
 * @param maxStringLength longest STRING attribute value, in characters after stripping
 */
@Validated
@ConfigurationProperties("reward.snapshot")
public record SnapshotProperties(@Positive int maxStringLength) {
}
