package com.rewardengine.core.client;

import com.rewardengine.common.audit.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * The caller identity resolved from an API key or mTLS certificate — never
 * from a request body field.
 *
 * Global rather than tenant-scoped on purpose: this row is how a request's
 * tenant gets resolved in the first place, so it has to be readable before
 * any TenantContext exists. tenantId is a plain column here, not @TenantId.
 *
 * allowedChannels scopes which campaign version channels this caller may use.
 */
@Getter
@Setter
@Entity
@Table(name = "client_application")
public class ClientApplication extends BaseEntity {

	@Column(nullable = false, updatable = false)
	private UUID tenantId;

	@Column(nullable = false, unique = true)
	private String clientKey;

	@Column(nullable = false)
	private String credentialHash;

	@ElementCollection
	@CollectionTable(name = "client_application_channel", joinColumns = @JoinColumn(name = "client_application_id"))
	@Column(name = "channel", nullable = false)
	private Set<String> allowedChannels = new HashSet<>();

	@Column(nullable = false)
	private int rateLimitPerMinute = 600;

	@Column(nullable = false)
	private boolean active = true;
}
