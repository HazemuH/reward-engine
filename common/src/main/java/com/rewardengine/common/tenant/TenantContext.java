package com.rewardengine.common.tenant;

/**
 * Holds the current tenant for the duration of one request.
 *
 * This is the generalization of "channel" / client_application from the
 * original design: instead of one bank with many
 * channels, a SaaS deployment has many tenants, each with its own channels.
 *
 * Resolved once per request by a filter/interceptor (from the API key, mTLS
 * client identity, or admin JWT claim — never from a request body field),
 * then read by anything that scopes a query or a cache key. Nothing in core
 * should query campaign/counter/redemption tables without going through a
 * path that has this set first; see .claude/skills/tenant-safety-check.
 */
public final class TenantContext {

	private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

	private TenantContext() {
	}

	public static void set(String tenantId) {
		CURRENT.set(tenantId);
	}

	public static String get() {
		String tenantId = CURRENT.get();
		if (tenantId == null) {
			throw new IllegalStateException(
					"TenantContext not set — resolve it from the request before touching tenant-scoped data");
		}
		return tenantId;
	}

	public static void clear() {
		CURRENT.remove();
	}
}
