package com.rewardengine.core.campaign;

import com.rewardengine.common.error.BusinessRuleException;
import com.rewardengine.common.text.StorableText;
import com.rewardengine.core.attribute.AttributeName;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Structural schema of campaign_version.raw_definition, checked on every draft
 * save (field-by-field reference: docs/architecture/rule-definition.md).
 *
 * Only shape is checked here, so a half-written draft still saves: every
 * top-level section is optional and may be empty. Whether the version is
 * complete enough to approve (has a benefit, a budget counter, content in the
 * tenant's languages, discounts only on ALL_OR_NOTHING counters) is decided at
 * submit, when content and counter limits are final together.
 *
 * Counter limits are not part of the definition: counter_def is their single
 * source of truth, authored through CounterDefService.
 */
final class RuleDefinitionValidator {

	// Duplicate keys rejected outright: jsonb would silently keep the last one,
	// so the editor would see something other than what gets evaluated.
	private static final JsonMapper MAPPER = JsonMapper.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.build();

	// BCP 47 language[-Script][-REGION|-UN M.49 region]: en, pt-BR, zh-Hant, es-419
	private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}(-[A-Z][a-z]{3})?(-([A-Z]{2}|[0-9]{3}))?");
	private static final Set<String> SECTIONS = Set.of("conditions", "benefits", "vars", "content");
	// ordered, so violations come out in the same order on every run
	private static final List<String> OPERAND_FIELDS = Arrays.stream(ConditionOperator.Operand.values())
			.flatMap(operand -> operand.fields().stream().sorted())
			.toList();
	private static final Set<String> CONDITION_FIELDS = Stream.concat(Stream.of("attr", "op"), OPERAND_FIELDS.stream())
			.collect(Collectors.toUnmodifiableSet());
	private static final Map<String, BenefitType> BENEFIT_TYPES = Arrays.stream(BenefitType.values())
			.collect(Collectors.toUnmodifiableMap(BenefitType::name, type -> type));
	private static final Set<String> CONTENT_FIELDS = Set.of("title", "description", "terms", "message");
	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private final List<String> violations = new ArrayList<>();
	private boolean unstorableKey;
	private final Map<String, JsonNode> declaredVars = new HashMap<>();

	private RuleDefinitionValidator() {
	}

	/** Throws RULE_DEFINITION_INVALID listing every violation found, not just the first. */
	static void validate(String rawDefinition) {
		List<String> found = new RuleDefinitionValidator().check(rawDefinition);
		if (!found.isEmpty()) {
			throw new BusinessRuleException("RULE_DEFINITION_INVALID",
					"Rule definition is invalid: " + String.join("; ", found), found);
		}
	}

	private List<String> check(String rawDefinition) {
		JsonNode root;
		try {
			root = MAPPER.readTree(rawDefinition);
		} catch (JacksonException e) {
			return List.of("not valid JSON: " + e.getOriginalMessage());
		}
		if (root == null || !root.isObject()) {
			return List.of("must be a JSON object");
		}
		checkStorable(root, "");
		if (unstorableKey) {
			// the checks below quote keys; stop before they repeat the characters just refused
			return violations;
		}
		for (Map.Entry<String, JsonNode> section : root.properties()) {
			if (section.getKey().equals("counters")) {
				violate("counters", "counter limits are authored as counter definitions, not in the rule definition");
			} else if (!SECTIONS.contains(section.getKey())) {
				violate(section.getKey(), "unknown field");
			}
		}
		// vars first: conditions may reference them
		if (root.has("vars")) {
			checkVars(root.get("vars"));
		}
		if (root.has("conditions")) {
			checkArray(root.get("conditions"), "conditions", this::checkCondition);
		}
		if (root.has("benefits")) {
			checkBenefits(root.get("benefits"));
		}
		if (root.has("content")) {
			checkContent(root.get("content"));
		}
		return violations;
	}

	private void checkVars(JsonNode vars) {
		if (!vars.isObject()) {
			violate("vars", "must be an object");
			return;
		}
		for (Map.Entry<String, JsonNode> var : vars.properties()) {
			String path = "vars." + var.getKey();
			if (!RuleIdentifier.isValid(var.getKey())) {
				violate(path, "name must be " + RuleIdentifier.RULE);
			} else if (!isScalar(var.getValue())) {
				violate(path, "must be a string, number or boolean");
			} else {
				// only valid vars are referenceable, so a condition naming a broken one is flagged too
				declaredVars.put(var.getKey(), var.getValue());
			}
		}
	}

	private void checkCondition(JsonNode condition, String path) {
		if (!checkFields(condition, path, CONDITION_FIELDS)) {
			return;
		}
		JsonNode attr = condition.get("attr");
		if (attr == null || !attr.isString() || !AttributeName.isValid(attr.stringValue())) {
			violate(path + ".attr", "required; " + AttributeName.RULE);
		}
		JsonNode opNode = condition.get("op");
		ConditionOperator op = opNode != null && opNode.isString()
				? ConditionOperator.fromSymbol(opNode.stringValue()).orElse(null)
				: null;
		if (op == null) {
			violate(path + ".op", "required; one of = != > >= < <= in not_in between");
			return;
		}
		Set<String> allowed = op.operand().fields();
		for (String field : OPERAND_FIELDS) {
			if (condition.has(field) && !allowed.contains(field)) {
				violate(path + "." + field, "not used by operator \"" + op.symbol() + "\"");
			}
		}
		switch (op.operand()) {
			case VALUE -> checkOperand(condition.get("value"), path + ".value", op);
			case SET -> checkSet(condition, path);
			case RANGE -> checkRange(condition, path, op);
		}
	}

	private void checkSet(JsonNode condition, String path) {
		JsonNode values = condition.get("values");
		JsonNode ref = condition.get("ref");
		if ((values == null) == (ref == null)) {
			violate(path, "needs exactly one of \"values\" or \"ref\"");
			return;
		}
		if (ref != null) {
			if (!ref.isString() || !RuleIdentifier.isValid(ref.stringValue())) {
				violate(path + ".ref", "must be a reference set name");
			}
			return;
		}
		if (!values.isArray() || values.isEmpty()) {
			violate(path + ".values", "must be a non-empty array");
			return;
		}
		for (int i = 0; i < values.size(); i++) {
			if (!isScalar(values.get(i))) {
				violate(path + ".values[" + i + "]", "must be a string, number or boolean");
			}
		}
	}

	private void checkRange(JsonNode condition, String path, ConditionOperator op) {
		JsonNode from = checkOperand(condition.get("from"), path + ".from", op);
		JsonNode to = checkOperand(condition.get("to"), path + ".to", op);
		if (from == null || to == null) {
			return;
		}
		if (from.isNumber() != to.isNumber()) {
			violate(path, "\"from\" and \"to\" must both be numbers or both be strings");
		} else if (from.isNumber() && from.decimalValue().compareTo(to.decimalValue()) > 0) {
			violate(path, "\"from\" is greater than \"to\"");
		}
	}

	/**
	 * A literal, or {"var": name} naming a declared var; type rules apply to the
	 * var's value as they would to the literal. Returns that value, or null if
	 * the operand is invalid.
	 */
	private JsonNode checkOperand(JsonNode operand, String path, ConditionOperator op) {
		if (operand == null) {
			violate(path, "required by operator \"" + op.symbol() + "\"");
			return null;
		}
		JsonNode value = operand;
		if (operand.isObject()) {
			JsonNode var = operand.get("var");
			if (operand.size() != 1 || var == null || !var.isString()) {
				violate(path, "must be a literal or {\"var\": name}");
				return null;
			}
			value = declaredVars.get(var.stringValue());
			if (value == null) {
				violate(path, "var \"" + var.stringValue() + "\" is not declared in vars");
				return null;
			}
		}
		if (!isScalar(value) || (op.ordered() && value.isBoolean())) {
			violate(path, op.ordered() ? "must be a string or number" : "must be a string, number or boolean");
			return null;
		}
		return value;
	}

	private void checkBenefits(JsonNode benefits) {
		Set<BenefitType> seen = EnumSet.noneOf(BenefitType.class);
		checkArray(benefits, "benefits", (benefit, path) -> {
			JsonNode typeNode = benefit.get("type");
			BenefitType type = typeNode != null && typeNode.isString() ? BENEFIT_TYPES.get(typeNode.stringValue()) : null;
			if (type == null) {
				violate(path + ".type", "required; one of DISCOUNT_FIXED, DISCOUNT_PERCENT, POINT, CASHBACK");
				return;
			}
			if (!seen.add(type)) {
				violate(path + ".type", type + " appears more than once");
			} else if (type.isDiscount() && seen.stream().filter(BenefitType::isDiscount).count() > 1) {
				violate(path + ".type", "at most one discount benefit per version");
			}
			switch (type) {
				case DISCOUNT_PERCENT -> {
					if (checkFields(benefit, path, Set.of("type", "percent", "max"))) {
						checkPercent(benefit.get("percent"), path + ".percent");
						checkPositiveInteger(benefit.get("max"), path + ".max");
					}
				}
				case DISCOUNT_FIXED, POINT, CASHBACK -> {
					if (checkFields(benefit, path, Set.of("type", "amount"))) {
						checkPositiveInteger(benefit.get("amount"), path + ".amount");
					}
				}
			}
		});
	}

	private void checkPercent(JsonNode percent, String path) {
		if (percent == null || !percent.isNumber()
				|| percent.decimalValue().signum() <= 0 || percent.decimalValue().compareTo(HUNDRED) > 0) {
			violate(path, "required; a number greater than 0 and at most 100");
		}
	}

	// Whole and bounded: amounts are summed into bigint MONEY/POINT counters.
	private void checkPositiveInteger(JsonNode amount, String path) {
		if (amount == null || !amount.isIntegralNumber() || !amount.canConvertToLong()
				|| !RuleAmount.isValid(amount.longValue())) {
			// 100.0 and 1e3 are refused too: amounts are written as JSON integers
			violate(path, "required; " + RuleAmount.RULE + ", written without a decimal point or exponent");
		}
	}

	/**
	 * Every key, string and number, at any depth: what jsonb would refuse or the
	 * driver silently alter (see StorableText), or a number past jsonb's numeric
	 * range, is reported here at its path rather than by the database.
	 */
	private void checkStorable(JsonNode node, String path) {
		if (node.isString() && !StorableText.isStorable(node.stringValue())) {
			violate(path, "contains characters that can't be stored");
		} else if (node.isNumber() && !fitsJsonb(node.decimalValue())) {
			violate(path, "number is outside the range that can be stored");
		} else if (node.isArray()) {
			for (int i = 0; i < node.size(); i++) {
				checkStorable(node.get(i), path + "[" + i + "]");
			}
		} else if (node.isObject()) {
			for (Map.Entry<String, JsonNode> field : node.properties()) {
				String fieldPath = path.isEmpty() ? field.getKey() : path + "." + field.getKey();
				if (!StorableText.isStorable(field.getKey())) {
					unstorableKey = true;
					violate(path.isEmpty() ? "(key)" : path, "has a key with characters that can't be stored");
				} else {
					checkStorable(field.getValue(), fieldPath);
				}
			}
		}
	}

	private void checkContent(JsonNode content) {
		if (!content.isObject()) {
			violate("content", "must be an object keyed by language, e.g. {\"en\": {\"title\": ...}}");
			return;
		}
		Set<String> firstFields = null;
		String firstLanguage = null;
		for (Map.Entry<String, JsonNode> language : content.properties()) {
			String path = "content." + language.getKey();
			if (!LANGUAGE.matcher(language.getKey()).matches()) {
				violate(path, "must be a language tag such as \"en\" or \"pt-BR\"");
				continue;
			}
			JsonNode texts = language.getValue();
			if (!checkFields(texts, path, CONTENT_FIELDS)) {
				continue;
			}
			for (Map.Entry<String, JsonNode> text : texts.properties()) {
				if (!text.getValue().isString() || text.getValue().stringValue().isBlank()) {
					violate(path + "." + text.getKey(), "must be non-blank text");
				}
			}
			// every language says the same things, so no outcome is untranslated
			Set<String> fields = new TreeSet<>(texts.propertyNames());
			if (firstFields == null) {
				firstFields = fields;
				firstLanguage = language.getKey();
			} else if (!fields.equals(firstFields)) {
				violate(path, "has fields " + fields + " but content." + firstLanguage + " has " + firstFields);
			}
		}
	}

	private interface ElementCheck {
		void check(JsonNode element, String path);
	}

	private void checkArray(JsonNode array, String name, ElementCheck check) {
		if (!array.isArray()) {
			violate(name, "must be an array");
			return;
		}
		for (int i = 0; i < array.size(); i++) {
			check.check(array.get(i), name + "[" + i + "]");
		}
	}

	/** Must be an object with no fields outside {@code allowed}; false if it isn't an object at all. */
	private boolean checkFields(JsonNode node, String path, Set<String> allowed) {
		if (!node.isObject()) {
			violate(path, "must be an object");
			return false;
		}
		for (String field : node.propertyNames()) {
			if (!allowed.contains(field)) {
				violate(path + "." + field, "unknown field");
			}
		}
		return true;
	}

	// jsonb numeric: at most 131072 digits before the decimal point and 16383 after;
	// zero fits whatever its exponent (Postgres reduces 0e200000 to 0)
	private static boolean fitsJsonb(BigDecimal number) {
		return number.signum() == 0
				|| (number.precision() - number.scale() <= 131_072 && number.scale() <= 16_383);
	}

	private static boolean isScalar(JsonNode node) {
		return node.isString() || node.isNumber() || node.isBoolean();
	}

	private void violate(String path, String problem) {
		violations.add(path + ": " + problem);
	}
}
