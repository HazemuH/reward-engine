package com.rewardengine.common.web;

import java.time.Instant;

/**
 * Standard envelope for every admin-facing response.
 * Internal transaction endpoints (calculate/reserve/confirm/...) do NOT use this
 * envelope — they return the raw contract shape documented in
 * docs/architecture/overview.md so the caller clients don't unwrap anything extra.
 */
public record ApiResponse<T>(boolean success, T data, ApiError error, Instant timestamp) {

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(true, data, null, Instant.now());
	}

	public static <T> ApiResponse<T> error(String code, String message) {
		return new ApiResponse<>(false, null, new ApiError(code, message), Instant.now());
	}

	public record ApiError(String code, String message) {
	}
}
