package com.rewardengine.core.tenant;

import com.rewardengine.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One tenant = one deployment customer (one bank, one issuer, ...). Global, not
 * tenant-scoped: this is the row every other table's tenant_id points at.
 */
@Getter
@Setter
@Entity
@Table(name = "tenant")
public class Tenant extends BaseEntity {

	@Column(nullable = false, unique = true)
	private String code;

	@Column(nullable = false)
	private String name;

	/** IANA zone for counter periods (DAY/WEEK/MONTH); periods are computed from server time in this zone. */
	@Column(nullable = false)
	private String timeZone;

	/** ISO 4217 code for every MONEY-unit counter and money benefit of this tenant. */
	@Column(nullable = false, length = 3)
	private String currency;

	@Column(nullable = false)
	private boolean active = true;
}
