package com.rewardengine.core.attribute;

import com.rewardengine.common.error.BusinessRuleException;
import com.rewardengine.common.text.StorableText;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenant's attribute catalog: the only attributes conditions and scripts
 * may refer to. Attributes are never removed — approved versions keep
 * referring to them.
 */
@Service
@Transactional(readOnly = true)
public class AttributeCatalogService {

	private static final int MAX_DESCRIPTION_LENGTH = 500;

	private final AttributeDefRepository defs;
	private final AttributeLocks locks;

	AttributeCatalogService(AttributeDefRepository defs, AttributeLocks locks) {
		this.defs = defs;
		this.locks = locks;
	}

	@Transactional
	public AttributeDef define(String name, AttributeSource source, AttributeDataType dataType, boolean sensitive,
			String description) {
		List<String> violations = new ArrayList<>();
		if (!AttributeName.isValid(name)) {
			violations.add("name: " + AttributeName.RULE);
		} else if (BuiltInAttribute.reserves(name)) {
			violations.add("name: \"" + name + "\" is supplied by the engine and can't be redefined or nested under");
		}
		if (source == null) {
			violations.add("source: required");
		}
		if (dataType == null) {
			violations.add("dataType: required");
		}
		String validDescription = description(description, violations);
		if (!violations.isEmpty()) {
			throw new BusinessRuleException("ATTRIBUTE_INVALID",
					"Attribute is invalid: " + String.join("; ", violations), violations);
		}
		// one define at a time per tenant: the nesting check below has no constraint behind it
		locks.lockCatalog();
		if (defs.existsByNameIgnoreCase(name)) {
			throw new BusinessRuleException("ATTRIBUTE_ALREADY_DEFINED", "Attribute " + name + " is already defined");
		}
		List<String> nesting = defs.findNamesNestingWith(name);
		if (!nesting.isEmpty()) {
			String problem = "name: \"" + name + "\" nests with " + nesting.stream().sorted().toList()
					+ "; an attribute can't be both a value and the parent of another";
			throw new BusinessRuleException("ATTRIBUTE_INVALID", "Attribute is invalid: " + problem, List.of(problem));
		}
		try {
			return defs.saveAndFlush(AttributeDef.define(name, source, dataType, sensitive, validDescription));
		} catch (DataIntegrityViolationException e) {
			if (!Constraints.violated(e, Constraints.ATTRIBUTE_NAME)) {
				throw e;
			}
			// backstop: defines are serialized by the catalog lock, but the unique index on
			// lower(name) is what holds if anything ever inserts without it
			throw new BusinessRuleException("ATTRIBUTE_ALREADY_DEFINED", "Attribute " + name + " is already defined");
		}
	}

	public List<AttributeDef> list() {
		return defs.findAllByOrderByNameAsc();
	}

	public AttributeDef get(UUID id) {
		return defs.findById(id).orElseThrow(
				() -> new BusinessRuleException("ATTRIBUTE_NOT_FOUND", "Attribute " + id + " not found"));
	}

	/** Only the sensitive flag and description change; name, source and type are fixed (see AttributeDef). */
	@Transactional
	public AttributeDef describe(UUID id, long expectedVersion, boolean sensitive, String description) {
		List<String> violations = new ArrayList<>();
		String validDescription = description(description, violations);
		if (!violations.isEmpty()) {
			throw new BusinessRuleException("ATTRIBUTE_INVALID",
					"Attribute is invalid: " + String.join("; ", violations), violations);
		}
		AttributeDef def = get(id);
		if (def.getVersion() != expectedVersion) {
			throw new ObjectOptimisticLockingFailureException(AttributeDef.class, id);
		}
		def.describe(sensitive, validDescription);
		return defs.saveAndFlush(def);
	}

	/**
	 * Fails with ATTRIBUTE_NOT_DEFINED listing every name that is neither in the
	 * catalog nor built in. For submit: a draft may name attributes that don't
	 * exist yet, an approved version may not.
	 */
	public void requireDefined(Collection<String> names) {
		Set<String> wanted = names.stream()
				.filter(name -> BuiltInAttribute.named(name).isEmpty())
				.collect(Collectors.toCollection(TreeSet::new));
		if (wanted.isEmpty()) {
			return;
		}
		// the whole (small) catalog, folded in Java: matching Java-lowered names against the
		// database's lower() would depend on its locale
		List<String> known = new ArrayList<>(Arrays.stream(BuiltInAttribute.values())
				.map(BuiltInAttribute::attributeName).toList());
		defs.findAll().forEach(def -> known.add(def.getName()));
		Map<String, String> spellingByLower = AttributeName.spellingByLower(known);
		wanted.removeIf(name -> name.equals(spellingByLower.get(name.toLowerCase(Locale.ROOT))));
		if (!wanted.isEmpty()) {
			List<String> missing = wanted.stream()
					.map(name -> AttributeName.notInCatalog(name, "in the attribute catalog", spellingByLower))
					.toList();
			throw new BusinessRuleException("ATTRIBUTE_NOT_DEFINED",
					"Attributes not in the catalog: " + String.join(", ", wanted), missing);
		}
	}

	private static String description(String description, List<String> violations) {
		String stripped = description == null ? "" : description.strip();
		if (StorableText.length(stripped) > MAX_DESCRIPTION_LENGTH) {
			violations.add("description: at most " + MAX_DESCRIPTION_LENGTH + " characters");
		} else if (!StorableText.isStorable(stripped)) {
			violations.add("description: contains characters that can't be stored");
		}
		return stripped.isEmpty() ? null : stripped;
	}
}
