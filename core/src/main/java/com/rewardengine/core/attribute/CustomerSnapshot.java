package com.rewardengine.core.attribute;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * A customer's snapshot attributes, typed by the catalog: String, BigDecimal,
 * Boolean, LocalDate or LocalDateTime per {@link AttributeDataType}.
 *
 * Two views, so where a value may go is decided by which one a caller asks for:
 * {@link #values()} for the engine's own data conditions (sensitive attributes
 * included — targeting on them is a condition, reviewed through approval), and
 * {@link #scriptView()} for anything a rule script sees, without them.
 * toString masks sensitive values and leaves out the user key, so logging a
 * snapshot leaks neither.
 */
public final class CustomerSnapshot {

	private static final String MASK = "***";

	private final String userKey;
	private final Instant loadedAt;
	private final Map<String, Object> values;
	private final Set<String> sensitive;

	CustomerSnapshot(String userKey, Instant loadedAt, Map<String, Object> values, Set<String> sensitive) {
		this.userKey = userKey;
		this.loadedAt = loadedAt;
		this.values = Map.copyOf(values);
		this.sensitive = Set.copyOf(sensitive);
	}

	public String userKey() {
		return userKey;
	}

	/** When the load these values came from began serving; how stale they may be. */
	public Instant loadedAt() {
		return loadedAt;
	}

	public Optional<Object> value(String attribute) {
		return Optional.ofNullable(values.get(attribute));
	}

	/** Engine-only: includes sensitive attributes. Never hand this to a script, a log line or an export. */
	public Map<String, Object> values() {
		return values;
	}

	/** What a rule script may read: every value except sensitive ones. */
	public Map<String, Object> scriptView() {
		return values.entrySet().stream()
				.filter(e -> !sensitive.contains(e.getKey()))
				.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	public boolean isSensitive(String attribute) {
		return sensitive.contains(attribute);
	}

	@Override
	public String toString() {
		Map<String, Object> shown = new LinkedHashMap<>();
		new TreeMap<>(values).forEach((name, value) -> shown.put(name, sensitive.contains(name) ? MASK : value));
		return "CustomerSnapshot[loadedAt=" + loadedAt + ", values=" + shown + "]";
	}
}
