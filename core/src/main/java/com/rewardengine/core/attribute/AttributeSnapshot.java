package com.rewardengine.core.attribute;

import com.rewardengine.common.audit.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One customer's snapshot attributes within one load. Written once, never
 * updated: the next load replaces the whole generation. Read through
 * {@link CustomerSnapshot}, which applies the catalog's types and keeps
 * sensitive values out of string forms.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "attribute_snapshot")
public class AttributeSnapshot extends TenantScopedEntity {

	@Column(nullable = false, updatable = false)
	private UUID loadId;

	@Column(nullable = false, updatable = false)
	private String userKey;

	/** Attribute name → ISO/JSON value; absent attributes are simply not present. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, updatable = false)
	private String attributes;

	static AttributeSnapshot of(UUID loadId, String userKey, String attributes) {
		AttributeSnapshot snapshot = new AttributeSnapshot();
		snapshot.loadId = loadId;
		snapshot.userKey = userKey;
		snapshot.attributes = attributes;
		return snapshot;
	}
}
