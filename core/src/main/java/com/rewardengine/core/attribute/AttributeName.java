package com.rewardengine.core.attribute;

import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Dotted attribute names such as {@code customer.segment}, as conditions and
 * the catalog write them. Scripts will read them as nested properties
 * ({@code facts.customer.segment}, RE-035), so no segment may be a Groovy or
 * Java reserved word or a property the sandbox has to block — compared without
 * case, as names are unique without case.
 */
public final class AttributeName {

	public static final String RULE = "a dotted attribute name such as \"customer.segment\" (at most 100 characters,"
			+ " no Groovy/Java reserved word or class/metaClass/properties as a segment)";

	private static final Pattern PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*");
	private static final int MAX_LENGTH = 100;
	private static final Set<String> RESERVED_SEGMENTS = Set.of(
			// Groovy keywords and contextual keywords
			"as", "assert", "break", "case", "catch", "class", "const", "continue", "def", "default", "do", "else",
			"enum", "extends", "false", "finally", "for", "goto", "if", "implements", "import", "in", "instanceof",
			"interface", "new", "null", "package", "return", "super", "switch", "this", "throw", "throws", "trait",
			"true", "try", "var", "void", "volatile", "while", "record", "sealed", "permits", "yield", "threadsafe",
			// Java modifiers and primitive types, reserved in Groovy source too
			"abstract", "final", "native", "private", "protected", "public", "static", "strictfp", "synchronized",
			"transient", "boolean", "byte", "char", "double", "float", "int", "long", "short",
			// properties the sandbox blocks
			"metaclass", "properties");

	private AttributeName() {
	}

	public static boolean isValid(String name) {
		return name != null && name.length() <= MAX_LENGTH && PATTERN.matcher(name).matches()
				&& Arrays.stream(name.split("\\.")).noneMatch(s -> RESERVED_SEGMENTS.contains(s.toLowerCase(Locale.ROOT)));
	}

	/** Lower-cased name → stored spelling, for {@link #notInCatalog}. */
	static Map<String, String> spellingByLower(Collection<String> storedNames) {
		return storedNames.stream()
				.collect(Collectors.toMap(name -> name.toLowerCase(Locale.ROOT), Function.identity(), (a, b) -> a));
	}

	/**
	 * "{@code name}: not {@code what}", naming the stored spelling when only the
	 * case differs — names are unique without case, so there is exactly one.
	 */
	static String notInCatalog(String name, String what, Map<String, String> spellingByLower) {
		String stored = spellingByLower.get(name.toLowerCase(Locale.ROOT));
		return stored == null ? name + ": not " + what : name + ": not " + what + "; it is spelled " + stored;
	}
}
