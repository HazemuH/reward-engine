package com.rewardengine.core.attribute;

import com.rewardengine.common.audit.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One attribute rules may use. Name, source and type are fixed once defined:
 * stored snapshot values and approved conditions are read through them, so
 * changing one would reinterpret data already written. A different meaning is
 * a new attribute.
 *
 * sensitive: may be used in data conditions (which go through approval), never
 * reaches a script, a log line or an export. Read at use time, so flagging an
 * attribute protects values already loaded.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "attribute_def")
public class AttributeDef extends TenantScopedEntity {

	@Column(nullable = false, updatable = false)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private AttributeSource source;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private AttributeDataType dataType;

	@Column(nullable = false)
	private boolean sensitive;

	private String description;

	/** Not validated here: AttributeCatalogService checks before defining. */
	static AttributeDef define(String name, AttributeSource source, AttributeDataType dataType, boolean sensitive,
			String description) {
		AttributeDef def = new AttributeDef();
		def.name = name;
		def.source = source;
		def.dataType = dataType;
		def.sensitive = sensitive;
		def.description = description;
		return def;
	}

	void describe(boolean sensitive, String description) {
		this.sensitive = sensitive;
		this.description = description;
	}
}
