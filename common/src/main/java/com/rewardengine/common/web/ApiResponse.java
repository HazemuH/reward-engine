package com.rewardengine.common.web;

import java.time.Instant;
import java.util.List;

/**
 * Standard envelope for every admin-facing response.
 * Internal transaction endpoints (calculate/reserve/confirm/...) do NOT use this
 * envelope — they return the raw contract shape documented in
 * docs/architecture/overview.md so machine callers don't unwrap anything extra.
 */
public record ApiResponse<T>(boolean success, T data, ApiError error, Instant timestamp) {

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(true, data, null, Instant.now());
	}

	public static <T> ApiResponse<T> error(String code, String message) {
		return error(code, message, List.of());
	}

	/** details: one entry per problem (BusinessRuleException#getDetails), so an editor can point at each field. */
	public static <T> ApiResponse<T> error(String code, String message, List<String> details) {
		return new ApiResponse<>(false, null, new ApiError(code, message, List.copyOf(details)), Instant.now());
	}

	public record ApiError(String code, String message, List<String> details) {
	}
}
