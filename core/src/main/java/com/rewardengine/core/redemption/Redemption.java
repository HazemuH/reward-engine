package com.rewardengine.core.redemption;

import com.rewardengine.common.audit.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One row per attempt to use a code/promo on one transaction. ruleVersionId
 * pins the exact CampaignVersion that was evaluated, so a later change to
 * the campaign never changes the outcome of a past redemption.
 */
@Getter
@Setter
@Entity
@Table(name = "redemption")
public class Redemption extends TenantScopedEntity {

	@Column(nullable = false, unique = true)
	private String requestId;

	@Column(nullable = false)
	private UUID campaignId;

	@Column(nullable = false)
	private UUID ruleVersionId;

	@Column(nullable = false)
	private String userKey;

	@Column(nullable = false)
	private String transactionRef;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private RedemptionStatus status;
}
