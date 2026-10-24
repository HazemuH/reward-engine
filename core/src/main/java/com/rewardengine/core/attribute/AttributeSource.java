package com.rewardengine.core.attribute;

/**
 * Where an attribute's value comes from at evaluation time. REQUEST: sent by
 * the caller with each request. SNAPSHOT: loaded periodically into
 * attribute_snapshot. A live LOOKUP source is deliberately absent: it would add
 * a downstream call to the calculate path (architecture overview, "Rule model").
 */
public enum AttributeSource {
	REQUEST,
	SNAPSHOT
}
