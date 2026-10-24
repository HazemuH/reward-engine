package com.rewardengine.core.campaign;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * Condition operators as written in a rule definition ({@code "op": ">="}).
 * The operand kind decides which fields a condition carries: {@code value} for
 * a comparison, {@code values} or {@code ref} for a set, {@code from}/{@code to}
 * (inclusive) for a range.
 */
public enum ConditionOperator {
	EQ("=", Operand.VALUE, false),
	NE("!=", Operand.VALUE, false),
	GT(">", Operand.VALUE, true),
	GTE(">=", Operand.VALUE, true),
	LT("<", Operand.VALUE, true),
	LTE("<=", Operand.VALUE, true),
	IN("in", Operand.SET, false),
	NOT_IN("not_in", Operand.SET, false),
	BETWEEN("between", Operand.RANGE, true);

	public enum Operand {
		VALUE(Set.of("value")),
		SET(Set.of("values", "ref")),
		RANGE(Set.of("from", "to"));

		private final Set<String> fields;

		Operand(Set<String> fields) {
			this.fields = fields;
		}

		/** The condition fields this operand kind is written with. */
		public Set<String> fields() {
			return fields;
		}
	}

	private final String symbol;
	private final Operand operand;
	private final boolean ordered;

	ConditionOperator(String symbol, Operand operand, boolean ordered) {
		this.symbol = symbol;
		this.operand = operand;
		this.ordered = ordered;
	}

	public String symbol() {
		return symbol;
	}

	public Operand operand() {
		return operand;
	}

	/** Compares by order, so a boolean operand is meaningless. */
	public boolean ordered() {
		return ordered;
	}

	public static Optional<ConditionOperator> fromSymbol(String symbol) {
		return Arrays.stream(values()).filter(op -> op.symbol.equals(symbol)).findFirst();
	}
}
