package com.rewardengine.core.campaign;

import com.rewardengine.common.audit.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Header row. The actual rule content (conditions/benefits/counters/script)
 * lives in {@link CampaignVersion} — a campaign is never edited in place
 * once a version is active; a new version is drafted instead (see
 * docs/architecture/overview.md section 03 "Aturan tidak pernah ditimpa").
 */
@Getter
@Setter
@Entity
@Table(name = "campaign")
public class Campaign extends TenantScopedEntity {

	@Column(nullable = false)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private CampaignStatus status = CampaignStatus.DRAFT;
}
