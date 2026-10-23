package com.rewardengine.core.attribute;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
	 * couldn't be stored: jsonb refuses NUL and unpaired surrogates, and the
	 * database error it raises quotes the JSON — a sensitive value would land in
	 * the logs. Strings are stripped, as CSV padding is not part of a value.
	 */
	static Object normalize(Object value, AttributeDataType type) {
		return switch (type) {
			case STRING -> value instanceof String s && storable(s) ? s.strip() : null;
			case NUMBER -> withinRange(decimal(value));
			case BOOLEAN -> value instanceof Boolean b ? b : null;
			case DATE -> value instanceof LocalDate d ? d.toString() : null;
			case DATETIME -> value instanceof LocalDateTime t ? t.toString() : null;
		};
	}

	static boolean isAbsent(Object value) {
		return value == null || (value instanceof String s && s.isBlank());
	}

	static String encode(Map<String, Object> normalized) {
		return MAPPER.writeValueAsString(normalized);
	}

	/** Typed values for the attributes still in {@code defs}; anything else stored is skipped. */
	static Map<String, Object> decode(String json, Map<String, AttributeDef> defs) {
		Map<String, Object> values = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : MAPPER.readTree(json).properties()) {
			AttributeDef def = defs.get(entry.getKey());
			if (def != null) {
				values.put(entry.getKey(), typed(entry.getValue(), def.getDataType()));
			}
		}
		return values;
	}

	private static Object typed(JsonNode node, AttributeDataType type) {
		return switch (type) {
			case STRING -> node.stringValue();
			case NUMBER -> node.decimalValue();
			case BOOLEAN -> node.booleanValue();
			case DATE -> LocalDate.parse(node.stringValue());
			case DATETIME -> LocalDateTime.parse(node.stringValue());
		};
	}

	private static boolean storable(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\u0000') {
				return false;
			}
			if (Character.isHighSurrogate(c)) {
				if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
					return false;
				}
				i++;
			} else if (Character.isLowSurrogate(c)) {
				return false;
			}
		}
		return true;
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
