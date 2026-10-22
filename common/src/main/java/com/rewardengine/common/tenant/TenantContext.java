package com.rewardengine.common.tenant;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The tenant the current unit of work runs as.
 *
 * Generalizes "channel" / client_application from the original design: one
 * deployment serves many tenants, each with its own channels. Bound once per
 * request (from the caller's API key, mTLS identity or admin JWT — never from
 * a request body field) and read by Hibernate through
 * {@link TenantIdentifierResolver}.
 *
 * Backed by a ScopedValue rather than a ThreadLocal: the binding ends when the
 * scope ends, so a forgotten clear() can't leak one tenant into the next
 * request on a pooled thread.
 */
public final class TenantContext {

	private static final ScopedValue<UUID> CURRENT = ScopedValue.newInstance();

	private TenantContext() {
	}

	public static <R, X extends Throwable> R callAs(UUID tenantId, ScopedValue.CallableOp<? extends R, X> op) throws X {
		return ScopedValue.where(CURRENT, Objects.requireNonNull(tenantId, "tenantId")).call(op);
	}

	public static void runAs(UUID tenantId, Runnable op) {
		ScopedValue.where(CURRENT, Objects.requireNonNull(tenantId, "tenantId")).run(op);
	}

	/** The bound tenant; throws if nothing bound one — use at boundaries where a tenant is mandatory. */
	public static UUID get() {
		if (!CURRENT.isBound()) {
			throw new IllegalStateException(
					"No tenant bound — wrap the call in TenantContext.callAs/runAs before touching tenant-scoped data");
		}
		return CURRENT.get();
	}

	public static Optional<UUID> current() {
		return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
	}
}
