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
 * One attempt to use a promo on one transaction. ruleVersionId pins the exact
 * CampaignVersion that was evaluated, so a later change to the campaign never
 * changes the outcome of a past redemption.
 *
 * requestId is unique per tenant, not globally — two tenants' callers may
 * legitimately reuse the same id.
 */
@Getter
@Setter
@Entity
@Table(name = "redemption")
public class Redemption extends TenantScopedEntity {

	@Column(nullable = false, updatable = false)
	private String requestId;

	@Column(nullable = false, updatable = false)
	private UUID campaignId;

	@Column(nullable = false, updatable = false)
	private UUID ruleVersionId;

	@Column(nullable = false, updatable = false)
	private String userKey;

	@Column(nullable = false, updatable = false)
	private String transactionRef;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private RedemptionStatus status;
}
