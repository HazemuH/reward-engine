package com.rewardengine.core.client;

import com.rewardengine.common.audit.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/**
 * The caller identity resolved from an API key or mTLS certificate (never
 * from a request body field).
 *
 * allowedChannels scopes which campaign.channels this caller may use. A
 * single tenant can have several client applications (e.g. one per channel,
 * or one for the transaction path and one for an internal batch job).
 */
@Getter
@Setter
@Entity
@Table(name = "client_application")
public class ClientApplication extends TenantScopedEntity {

	@Column(nullable = false, unique = true)
	private String clientKey;

	@Column(nullable = false)
	private String credentialHash;

	@ElementCollection
	private Set<String> allowedChannels;

	@Column(nullable = false)
	private int rateLimitPerMinute = 600;

	@Column(nullable = false)
	private boolean active = true;
}
