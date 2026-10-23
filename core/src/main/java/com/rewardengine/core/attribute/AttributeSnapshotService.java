package com.rewardengine.core.attribute;

import com.rewardengine.common.error.BusinessRuleException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer attribute snapshots, loaded in generations: a loader opens a load,
 * appends rows in batches, then activates it, which supersedes the previous
 * load in the same transaction. Reads only ever see the ACTIVE load, so a
 * half-written load is never served and the swap is atomic. Where rows come
 * from (file or API) is the loader's business (RE-057).
 *
 * Validation messages name the row and attribute, never the value: a rejected
 * value may be sensitive.
 */
@Service
@Transactional(readOnly = true)
public class AttributeSnapshotService {

	private static final int MAX_USER_KEY_LENGTH = 200;
	// keeps the duplicate-key lookup's IN list well inside the driver's bind-parameter limit
	static final int MAX_BATCH_ROWS = 10_000;

	private final SnapshotLoadRepository loads;
	private final AttributeSnapshotRepository snapshots;
	private final AttributeDefRepository defs;

	public AttributeSnapshotService(SnapshotLoadRepository loads, AttributeSnapshotRepository snapshots,
			AttributeDefRepository defs) {
		this.loads = loads;
		this.snapshots = snapshots;
		this.defs = defs;
	}

	@Transactional
	public SnapshotLoad beginLoad() {
		return loads.saveAndFlush(new SnapshotLoad());
	}

	/**
	 * All or nothing per batch: one invalid row rejects the batch with every
	 * problem listed (SNAPSHOT_ROWS_INVALID), so the loader can drop or fix those
	 * rows and resend.
	 */
	@Transactional
	public SnapshotLoad append(UUID loadId, List<SnapshotRow> rows) {
		SnapshotLoad load = getLoad(loadId);
		load.requireLoading();
		if (rows.size() > MAX_BATCH_ROWS) {
			String problem = "rows: at most " + MAX_BATCH_ROWS + " per batch";
			throw new BusinessRuleException("SNAPSHOT_ROWS_INVALID", problem, List.of(problem));
		}
		Map<String, AttributeDef> snapshotDefs = snapshotDefs();
		List<String> violations = new ArrayList<>();
		List<AttributeSnapshot> valid = new ArrayList<>();
		Map<String, Integer> rowByUserKey = new HashMap<>();
		for (int i = 0; i < rows.size(); i++) {
			SnapshotRow row = rows.get(i);
			String path = "rows[" + i + "]";
			if (row == null) {
				violations.add(path + ": must not be null");
				continue;
			}
			String userKey = userKey(row.userKey());
			if (userKey.isEmpty() || userKey.length() > MAX_USER_KEY_LENGTH) {
				violations.add(path + ".userKey: required, at most " + MAX_USER_KEY_LENGTH + " characters");
			} else if (rowByUserKey.putIfAbsent(userKey, i) != null) {
				violations.add(path + ".userKey: same customer as rows[" + rowByUserKey.get(userKey) + "]");
			}
			Map<String, Object> normalized = new LinkedHashMap<>();
			row.values().forEach((name, value) -> {
				AttributeDef def = snapshotDefs.get(name);
				if (def == null) {
					violations.add(path + "." + name + ": not a SNAPSHOT attribute in the catalog");
				} else if (!SnapshotValues.isAbsent(value)) {
					Object stored = SnapshotValues.normalize(value, def.getDataType());
					if (stored == null) {
						violations.add(path + "." + name + ": expected a " + def.getDataType() + " value");
					} else {
						normalized.put(name, stored);
					}
				}
			});
			valid.add(AttributeSnapshot.of(loadId, userKey, SnapshotValues.encode(normalized)));
		}
		if (!rowByUserKey.isEmpty()) {
			for (String loaded : snapshots.findUserKeysInLoad(loadId, rowByUserKey.keySet())) {
				violations.add("rows[" + rowByUserKey.get(loaded) + "].userKey: customer already in this load");
			}
		}
		if (!violations.isEmpty()) {
			throw new BusinessRuleException("SNAPSHOT_ROWS_INVALID",
					violations.size() + " problem(s) in snapshot batch: " + String.join("; ", violations), violations);
		}
		load.appended(valid.size());
		try {
			snapshots.saveAll(valid);
			return loads.saveAndFlush(load);
		} catch (DataIntegrityViolationException e) {
			// The database's message can quote the row's JSON, sensitive values included:
			// report without it. Reached by a concurrent append of the same customer.
			String problem = "batch: rejected by the database; a customer in it was appended concurrently";
			throw new BusinessRuleException("SNAPSHOT_ROWS_INVALID", problem, List.of(problem));
		}
	}

	/**
	 * Starts serving this load and supersedes the one serving until now, in one
	 * transaction. The old ACTIVE row is flushed before this one changes at all:
	 * the one-ACTIVE index is checked per statement, and a single flush would
	 * order the two updates as it pleases.
	 *
	 * Activations that overlap: the one that commits first wins; the other's
	 * supersede fails its @Version check (a load was serving) or its own update
	 * hits the one-ACTIVE index (none was), and it rolls back, its load still
	 * LOADING. Activations that don't overlap each supersede the previous one.
	 */
	@Transactional
	public SnapshotLoad activate(UUID loadId) {
		SnapshotLoad load = getLoad(loadId);
		load.requireLoading();
		loads.findByStatus(SnapshotLoadStatus.ACTIVE).ifPresent(current -> {
			current.supersede();
			loads.saveAndFlush(current);
		});
		load.activate(Instant.now());
		return loads.saveAndFlush(load);
	}

	@Transactional
	public SnapshotLoad fail(UUID loadId) {
		SnapshotLoad load = getLoad(loadId);
		load.fail();
		return loads.saveAndFlush(load);
	}

	/** The load serving reads, if any; its activatedAt is the "last loaded" time an admin sees. */
	public Optional<SnapshotLoad> activeLoad() {
		return loads.findByStatus(SnapshotLoadStatus.ACTIVE);
	}

	/** Empty when nothing is loaded for this customer: conditions on snapshot attributes are then false. */
	public Optional<CustomerSnapshot> find(String userKey) {
		String key = userKey(userKey);
		return snapshots.findActive(key).map(active -> {
			Map<String, AttributeDef> snapshotDefs = snapshotDefs();
			Set<String> sensitive = snapshotDefs.values().stream()
					.filter(AttributeDef::isSensitive)
					.map(AttributeDef::getName)
					.collect(Collectors.toCollection(HashSet::new));
			return new CustomerSnapshot(key, active.loadedAt(),
					SnapshotValues.decode(active.snapshot().getAttributes(), snapshotDefs), sensitive);
		});
	}

	/** Same key on write and read, so padding from a file never hides a loaded customer. */
	private static String userKey(String userKey) {
		return userKey == null ? "" : userKey.strip();
	}

	private SnapshotLoad getLoad(UUID loadId) {
		return loads.findById(loadId).orElseThrow(
				() -> new BusinessRuleException("SNAPSHOT_LOAD_NOT_FOUND", "Snapshot load " + loadId + " not found"));
	}

	private Map<String, AttributeDef> snapshotDefs() {
		return defs.findBySource(AttributeSource.SNAPSHOT).stream()
				.collect(Collectors.toMap(AttributeDef::getName, Function.identity()));
	}
}
