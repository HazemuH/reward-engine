package com.rewardengine.core.campaign;

import com.rewardengine.common.audit.TenantScopedEntity;
import com.rewardengine.common.error.BusinessRuleException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One version of a campaign's rules. Content is frozen once it leaves DRAFT —
 * a change is a new version, never an edit (architecture overview, "Rules are
 * never overwritten"). Counter limits live in {@link com.rewardengine.core.counter.CounterDef}
 * rows owned by this version.
 *
 * approvedBy/approvedAt are required from APPROVED onward (DB check constraint),
 * and the approver must differ from the author (enforced in the approval service).
 */
@Getter
@Setter
@Entity
@Table(name = "campaign_version")
public class CampaignVersion extends TenantScopedEntity {

	@Column(nullable = false, updatable = false)
	private UUID campaignId;

	@Column(nullable = false, updatable = false)
	private int versionNumber;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private CampaignVersionStatus status = CampaignVersionStatus.DRAFT;

	/** conditions, benefits, vars, content (id/en) — validated against a schema before submit. */
	@Setter(AccessLevel.NONE)
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private String rawDefinition;

	/** Optional rule script source; null for data-only rules (most of them). */
	@Setter(AccessLevel.NONE)
	@Column(columnDefinition = "text")
	private String optionalScript;

	@Setter(AccessLevel.NONE)
	@ElementCollection
	@CollectionTable(name = "campaign_version_channel", joinColumns = @JoinColumn(name = "campaign_version_id"))
	@Column(name = "channel", nullable = false)
	private Set<String> channels = new HashSet<>();

	private String approvedBy;

	private Instant approvedAt;

	/**
	 * The only write path for rule content, so "frozen once it leaves DRAFT" holds
	 * no matter which service holds the entity.
	 */
	public void editDraft(DraftContent content) {
		if (status != CampaignVersionStatus.DRAFT) {
			throw new BusinessRuleException("VERSION_NOT_EDITABLE",
					"Version " + versionNumber + " is " + status + "; changes go into a new draft version");
		}
		rawDefinition = content.rawDefinition();
		optionalScript = content.optionalScript();
		// retain+add rather than clear+add: an unchanged save stays clean and doesn't bump @Version
		channels.retainAll(content.channels());
		channels.addAll(content.channels());
	}
}
