package com.rewardengine.core.attribute;

/**
 * LOADING → ACTIVE → SUPERSEDED, or LOADING → FAILED. Exactly one ACTIVE load
 * per tenant serves reads (partial unique index); activating a new one
 * supersedes it in the same transaction, which is the atomic swap.
 */
public enum SnapshotLoadStatus {
	LOADING,
	ACTIVE,
	SUPERSEDED,
	FAILED
}
