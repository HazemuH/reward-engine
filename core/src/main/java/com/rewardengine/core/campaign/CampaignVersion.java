package com.rewardengine.core.campaign;

import com.rewardengine.common.audit.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * Immutable once APPROVED. conditions/benefits/counters are stored as JSON
 * (rawDefinition) per docs/architecture/overview.md section 03 — the engine
 * reads a validated snapshot, never a live-editable row. optionalScript is
 * the sandboxed Groovy source (50ms budget, no I/O; see
 * .claude/skills/money-safety-check for the checklist before changing
 * anything that touches script execution).
 *
 * channels controls which client applications may use this version — see
 * ClientApplication#allowedChannels.
 */
@Getter
@Setter
@Entity
@Table(name = "campaign_version")
public class CampaignVersion extends TenantScopedEntity {

	@Column(nullable = false)
	private UUID campaignId;

	@Column(nullable = false)
	private int versionNumber;

	@Lob
	@Column(nullable = false)
	private String rawDefinition; // JSON: conditions, benefits, counters, vars, content (id/en)

	@Lob
	private String optionalScript; // sandboxed Groovy source, or null

	@ElementCollection
	private Set<String> channels;

	@Column(nullable = false)
	private String approvedBy;
}
