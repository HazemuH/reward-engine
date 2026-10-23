package com.rewardengine.core.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rewardengine.common.error.BusinessRuleException;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleDefinitionValidatorTest {

	private static final String FULL = """
			{
			  "vars": { "startDate": "2026-08-01T00:00:00", "endDate": "2026-10-31T23:59:59", "minAmount": 20000 },
			  "conditions": [
			    { "attr": "amount", "op": ">=", "value": { "var": "minAmount" } },
			    { "attr": "customer.segment", "op": "in", "values": ["GOLD", "PLATINUM"] },
			    { "attr": "activityType", "op": "not_in", "ref": "excluded_activities" },
			    { "attr": "now", "op": "between", "from": { "var": "startDate" }, "to": { "var": "endDate" } },
			    { "attr": "amount", "op": "between", "from": 1, "to": 1 },
			    { "attr": "customer.employee", "op": "=", "value": false }
			  ],
			  "benefits": [
			    { "type": "DISCOUNT_PERCENT", "percent": 12.5, "max": 100000 },
			    { "type": "POINT", "amount": 50 }
			  ],
			  "content": {
			    "id": { "title": "Poin top up", "message": "Kamu dapat {points} poin" },
			    "en": { "title": "Top-up points", "message": "You earned {points} points" }
			  }
			}""";

	@Test
	void acceptsEveryConstructOfTheSchema() {
		assertThatCode(() -> RuleDefinitionValidator.validate(FULL)).doesNotThrowAnyException();
	}

	@Test
	void acceptsAnEmptyDraft() {
		assertThatCode(() -> RuleDefinitionValidator.validate("{}")).doesNotThrowAnyException();
		assertThatCode(() -> RuleDefinitionValidator.validate("{\"conditions\": [], \"benefits\": []}"))
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsWhatIsNotAJsonObject() {
		assertThat(violations("not json")).singleElement().asString().startsWith("not valid JSON");
		assertThat(violations("[]")).containsExactly("must be a JSON object");
		assertThat(violations("{} {}")).singleElement().asString().startsWith("not valid JSON");
	}

	@Test
	void rejectsDuplicateKeysThatJsonbWouldSilentlyCollapse() {
		assertThat(violations("{\"benefits\": [], \"benefits\": [{\"type\": \"POINT\", \"amount\": 1}]}"))
				.singleElement().asString().startsWith("not valid JSON").contains("benefits");
	}

	@Test
	void rejectsCountersAndUnknownSections() {
		assertThat(violations("{\"counters\": [], \"trigger\": \"AUTO\"}")).containsExactlyInAnyOrder(
				"counters: counter limits are authored as counter definitions, not in the rule definition",
				"trigger: unknown field");
	}

	@Test
	void collectsEveryConditionViolationWithItsPath() {
		assertThat(violations("""
				{ "conditions": [
				  { "attr": "1bad", "op": "=", "value": 1 },
				  { "attr": "amount", "op": "~", "value": 1 },
				  { "attr": "amount", "op": ">", "value": true },
				  { "attr": "amount", "op": "=", "values": [1] },
				  { "attr": "segment", "op": "in", "values": [] },
				  { "attr": "segment", "op": "in", "values": ["A"], "ref": "set" },
				  { "attr": "segment", "op": "in", "values": [["nested"]] },
				  { "attr": "amount", "op": "between", "from": 10, "to": 1 },
				  { "attr": "now", "op": "<", "value": { "var": "undeclared" } },
				  { "attr": "amount", "op": "=", "value": null, "extra": 1 }
				] }""")).containsExactly(
				"conditions[0].attr: required; a dotted attribute name such as \"customer.segment\"",
				"conditions[1].op: required; one of = != > >= < <= in not_in between",
				"conditions[2].value: must be a string or number",
				"conditions[3].values: not used by operator \"=\"",
				"conditions[3].value: required by operator \"=\"",
				"conditions[4].values: must be a non-empty array",
				"conditions[5]: needs exactly one of \"values\" or \"ref\"",
				"conditions[6].values[0]: must be a string, number or boolean",
				"conditions[7]: \"from\" is greater than \"to\"",
				"conditions[8].value: var \"undeclared\" is not declared in vars",
				"conditions[9].extra: unknown field",
				"conditions[9].value: must be a string, number or boolean");
	}

	@Test
	void varOperandsFollowTheSameTypeRulesAsLiterals() {
		assertThat(violations("""
				{
				  "vars": { "flag": true, "start": "2026-01-01", "1bad": 5 },
				  "conditions": [
				    { "attr": "amount", "op": ">", "value": { "var": "flag" } },
				    { "attr": "amount", "op": "between", "from": { "var": "start" }, "to": 5 },
				    { "attr": "amount", "op": "=", "value": { "var": "1bad" } },
				    { "attr": "amount", "op": "=", "value": { "var": "flag", "extra": 1 } }
				  ]
				}""")).containsExactly(
				"vars.1bad: name must be a letter followed by letters, digits or '_' (at most 100)",
				"conditions[0].value: must be a string or number",
				"conditions[1]: \"from\" and \"to\" must both be numbers or both be strings",
				"conditions[2].value: var \"1bad\" is not declared in vars",
				"conditions[3].value: must be a literal or {\"var\": name}");
	}

	@Test
	void collectsBenefitViolations() {
		assertThat(violations("""
				{ "benefits": [
				  { "type": "FREE_LUNCH" },
				  { "type": "DISCOUNT_FIXED", "amount": 0 },
				  { "type": "DISCOUNT_PERCENT", "percent": 120 },
				  { "type": "POINT", "amount": 10.5, "max": 3 },
				  { "type": "POINT", "amount": 1 },
				  { "type": "CASHBACK", "amount": 99999999999999999999 }
				] }""")).containsExactly(
				"benefits[0].type: required; one of DISCOUNT_FIXED, DISCOUNT_PERCENT, POINT, CASHBACK",
				"benefits[1].amount: required; a whole number greater than 0",
				"benefits[2].type: at most one discount benefit per version",
				"benefits[2].percent: required; a number greater than 0 and at most 100",
				"benefits[2].max: required; a whole number greater than 0",
				"benefits[3].max: unknown field",
				"benefits[3].amount: required; a whole number greater than 0",
				"benefits[4].type: POINT appears more than once",
				"benefits[5].amount: required; a whole number greater than 0");
	}

	@Test
	void collectsVarAndContentViolations() {
		assertThat(violations("""
				{
				  "vars": { "ok": 1, "bad-name": 2, "nested": { "a": 1 } },
				  "content": {
				    "id": { "title": "Judul", "message": "Pesan" },
				    "en": { "title": "Title" },
				    "EN": { "title": "x" },
				    "fr": { "title": " ", "footer": "x" }
				  }
				}""")).containsExactly(
				"vars.bad-name: name must be a letter followed by letters, digits or '_' (at most 100)",
				"vars.nested: must be a string, number or boolean",
				"content.en: has fields [title] but content.id has [message, title]",
				"content.EN: must be a language tag such as \"en\" or \"pt-BR\"",
				"content.fr.footer: unknown field",
				"content.fr.title: must be non-blank text",
				"content.fr: has fields [footer, title] but content.id has [message, title]");
	}

	@Test
	void exceptionCarriesCodeSummaryAndDetails() {
		assertThatThrownBy(() -> RuleDefinitionValidator.validate("{\"vars\": [], \"benefits\": {}}"))
				.isInstanceOfSatisfying(BusinessRuleException.class, e -> {
					assertThat(e.getCode()).isEqualTo("RULE_DEFINITION_INVALID");
					assertThat(e.getDetails()).containsExactly("vars: must be an object", "benefits: must be an array");
					assertThat(e.getMessage()).contains("vars: must be an object; benefits: must be an array");
				});
	}

	private static List<String> violations(String raw) {
		try {
			RuleDefinitionValidator.validate(raw);
			return List.of();
		} catch (BusinessRuleException e) {
			return e.getDetails();
		}
	}
}
