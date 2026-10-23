package com.rewardengine.core.attribute;

import java.time.Instant;

/** A customer's row in the serving load, with when that load began serving. */
record ActiveSnapshot(AttributeSnapshot snapshot, Instant loadedAt) {
}
