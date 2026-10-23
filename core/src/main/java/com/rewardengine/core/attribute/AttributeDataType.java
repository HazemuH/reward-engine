package com.rewardengine.core.attribute;

/**
 * DATE and DATETIME are local to the tenant's time zone (Tenant#timeZone), the
 * same zone counter periods use; stored as ISO-8601 text.
 */
public enum AttributeDataType {
	STRING,
	NUMBER,
	BOOLEAN,
	DATE,
	DATETIME
}
