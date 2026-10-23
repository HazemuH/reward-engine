package com.rewardengine.core.attribute;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Converts snapshot values between the loader's Java types, the jsonb column
 * and the catalog's types. Dates travel as ISO-8601 text; numbers as exact
 * decimals.
 */
final class SnapshotValues {

	private static final JsonMapper MAPPER = JsonMapper.builder()
			.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.build();

	// Far beyond any customer attribute, far inside what jsonb stores and Jackson
	// reads back (jsonb prints 1E+1500 as 1501 digits; Jackson stops at 1000).
	private static final int MAX_INTEGER_DIGITS = 30;
	private static final int MAX_FRACTION_DIGITS = 20;

	private SnapshotValues() {
	}

	/**
	 * The value in its stored form, or null if it doesn't fit {@code type} or
	 * couldn't be stored (see {@link StorableText}). Strings are stripped first,
	 * as CSV padding is not part of a value, then measured in characters.
	 */
	static Object normalize(Object value, AttributeDataType type, int maxStringLength) {
		return switch (type) {
			case STRING -> value instanceof String s ? string(s.strip(), maxStringLength) : null;
			case NUMBER -> withinRange(decimal(value));
			case BOOLEAN -> value instanceof Boolean b ? b : null;
			case DATE -> value instanceof LocalDate d ? d.toString() : null;
			case DATETIME -> value instanceof LocalDateTime t ? t.toString() : null;
		};
	}

	/** What {@link #normalize} accepts for {@code type}, for a validation message that can't quote the value. */
	static String expectation(AttributeDataType type, int maxStringLength) {
		return switch (type) {
			case STRING -> "a STRING of at most " + maxStringLength + " characters";
			case NUMBER -> "a NUMBER with at most " + MAX_INTEGER_DIGITS + " integer and " + MAX_FRACTION_DIGITS
					+ " fraction digits";
			case BOOLEAN, DATE, DATETIME -> "a " + type + " value";
		};
	}

	static boolean isAbsent(Object value) {
		return value == null || (value instanceof String s && s.isBlank());
	}

	static String encode(Map<String, Object> normalized) {
		return MAPPER.writeValueAsString(normalized);
	}

	/**
	 * Typed values for the attributes still in {@code defs}; anything else stored
	 * is skipped. A value that no longer reads as its type is treated as absent
	 * (reads fail open: its conditions are false) rather than thrown — parse
	 * errors quote the text, which may be a sensitive value.
	 */
	static Map<String, Object> decode(String json, Map<String, AttributeDef> defs) {
		Map<String, Object> values = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : MAPPER.readTree(json).properties()) {
			AttributeDef def = defs.get(entry.getKey());
			Object value = def == null ? null : typed(entry.getValue(), def.getDataType());
			if (value != null) {
				values.put(entry.getKey(), value);
			}
		}
		return values;
	}

	private static Object typed(JsonNode node, AttributeDataType type) {
		try {
			return switch (type) {
				case STRING -> node.isString() ? node.stringValue() : null;
				case NUMBER -> node.isNumber() ? node.decimalValue() : null;
				case BOOLEAN -> node.isBoolean() ? node.booleanValue() : null;
				case DATE -> node.isString() ? LocalDate.parse(node.stringValue()) : null;
				case DATETIME -> node.isString() ? LocalDateTime.parse(node.stringValue()) : null;
			};
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private static String string(String s, int maxLength) {
		return s.codePointCount(0, s.length()) <= maxLength && StorableText.isStorable(s) ? s : null;
	}

	private static BigDecimal withinRange(BigDecimal d) {
		if (d == null || d.precision() - d.scale() > MAX_INTEGER_DIGITS || d.scale() > MAX_FRACTION_DIGITS) {
			return null;
		}
		return d;
	}

	private static BigDecimal decimal(Object value) {
		return switch (value) {
			case BigDecimal d -> d;
			case BigInteger i -> new BigDecimal(i);
			case Long l -> BigDecimal.valueOf(l);
			case Integer i -> BigDecimal.valueOf(i);
			case Short s -> BigDecimal.valueOf(s);
			case Byte b -> BigDecimal.valueOf(b);
			case Double d when Double.isFinite(d) -> BigDecimal.valueOf(d);
			case Float f when Float.isFinite(f) -> new BigDecimal(f.toString());
			case null, default -> null;
		};
	}
}
