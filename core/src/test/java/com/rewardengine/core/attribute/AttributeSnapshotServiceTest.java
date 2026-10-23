package com.rewardengine.core.attribute;

import static com.rewardengine.core.TestSupport.assertRejected;
import static com.rewardengine.core.TestSupport.newTenant;
import static com.rewardengine.core.TestSupport.race;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rewardengine.common.error.BusinessRuleException;
import com.rewardengine.common.tenant.TenantContext;
import com.rewardengine.core.TestcontainersConfiguration;
import com.rewardengine.core.tenant.TenantRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AttributeSnapshotServiceTest {

	@Autowired
	TenantRepository tenants;

	@Autowired
	AttributeCatalogService catalog;

	@Autowired
	AttributeSnapshotService service;

	@Autowired
	SnapshotLoadRepository loads;

	@Test
	void loadIsNotServedUntilActivatedThenValuesComeBackTyped() {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad load = TenantContext.callAs(tenant, () -> service.beginLoad());
		TenantContext.callAs(tenant, () -> service.append(load.getId(), List.of(new SnapshotRow("u-1", Map.of(
				"customer.segment", "GOLD",
				"customer.tenureMonths", 18,
				"customer.employee", true,
				"customer.registeredOn", LocalDate.of(2024, 2, 29),
				"customer.lastLogin", LocalDateTime.of(2026, 10, 1, 8, 30))))));

		assertThat(TenantContext.callAs(tenant, () -> service.find("u-1"))).isEmpty();

		SnapshotLoad active = TenantContext.callAs(tenant, () -> service.activate(load.getId()));
		CustomerSnapshot snapshot = TenantContext.callAs(tenant, () -> service.find("u-1")).orElseThrow();

		assertThat(active.getRowCount()).isOne();
		assertThat(snapshot.loadedAt()).isEqualTo(active.getActivatedAt()).isNotNull();
		assertThat(snapshot.values()).containsOnly(
				Map.entry("customer.segment", "GOLD"),
				Map.entry("customer.tenureMonths", new BigDecimal("18")),
				Map.entry("customer.employee", true),
				Map.entry("customer.registeredOn", LocalDate.of(2024, 2, 29)),
				Map.entry("customer.lastLogin", LocalDateTime.of(2026, 10, 1, 8, 30)));
		assertThat(TenantContext.callAs(tenant, () -> service.find("unknown"))).isEmpty();
	}

	@Test
	void activatingANewLoadSwapsTheWholeGeneration() {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad first = load(tenant, row("u-1", "GOLD"), row("u-2", "SILVER"));
		TenantContext.callAs(tenant, () -> service.activate(first.getId()));
		SnapshotLoad second = load(tenant, row("u-1", "PLATINUM"));

		TenantContext.callAs(tenant, () -> service.activate(second.getId()));

		assertThat(TenantContext.callAs(tenant, () -> service.find("u-1")).orElseThrow().value("customer.segment"))
				.contains("PLATINUM");
		assertThat(TenantContext.callAs(tenant, () -> service.find("u-2"))).isEmpty();
		assertThat(TenantContext.callAs(tenant, () -> loads.findById(first.getId())).orElseThrow().getStatus())
				.isEqualTo(SnapshotLoadStatus.SUPERSEDED);
		assertThat(TenantContext.callAs(tenant, () -> service.activeLoad()).orElseThrow().getId())
				.isEqualTo(second.getId());
	}

	@Test
	void absentValuesAreNotStored() {
		UUID tenant = tenantWithCatalog();
		Map<String, Object> values = new HashMap<>();
		values.put("customer.segment", "  ");
		values.put("customer.tenureMonths", null);
		values.put("customer.employee", false);
		SnapshotLoad load = load(tenant, new SnapshotRow("u-1", values));
		TenantContext.callAs(tenant, () -> service.activate(load.getId()));

		assertThat(TenantContext.callAs(tenant, () -> service.find("u-1")).orElseThrow().values())
				.containsOnly(Map.entry("customer.employee", false));
	}

	@Test
	void invalidBatchIsRejectedWholeWithoutEchoingAnyValue() {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad load = load(tenant, row("u-0", "GOLD"));
		List<SnapshotRow> batch = Arrays.asList(
				new SnapshotRow("u-1", Map.of("customer.gender", 42, "amount", 5, "customer.unknown", "x")),
				new SnapshotRow(" ", Map.of()),
				row("u-2", "GOLD"),
				row("u-2", "SILVER"),
				row("u-0", "GOLD"),
				null);

		assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> service.append(load.getId(), batch)))
				.isInstanceOfSatisfying(BusinessRuleException.class, e -> {
					assertThat(e.getCode()).isEqualTo("SNAPSHOT_ROWS_INVALID");
					assertThat(e.getDetails()).containsExactlyInAnyOrder(
							"rows[0].customer.gender: expected a STRING value",
							"rows[0].amount: not a SNAPSHOT attribute in the catalog",
							"rows[0].customer.unknown: not a SNAPSHOT attribute in the catalog",
							"rows[1].userKey: required, at most 200 characters",
							"rows[3].userKey: same customer as rows[2]",
							"rows[4].userKey: customer already in this load",
							"rows[5]: must not be null");
					assertThat(e.getMessage()).doesNotContain("42").doesNotContain("u-2");
				});
		assertThat(TenantContext.callAs(tenant, () -> loads.findById(load.getId())).orElseThrow().getRowCount())
				.isOne();
	}

	@Test
	void valuesTheDatabaseCouldNotStoreAreRejectedWithoutEchoingThem() {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad load = TenantContext.callAs(tenant, () -> service.beginLoad());
		List<SnapshotRow> batch = List.of(
				new SnapshotRow("u-1", Map.of("customer.gender", "F\u0000secret")),
				new SnapshotRow("u-2", Map.of("customer.gender", "\uD83D")),
				new SnapshotRow("u-3", Map.of("customer.tenureMonths", new BigDecimal("1E+1500"))),
				new SnapshotRow("u-4", Map.of("customer.tenureMonths", new BigDecimal("0.000000000000000000001"))));

		assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> service.append(load.getId(), batch)))
				.isInstanceOfSatisfying(BusinessRuleException.class, e -> {
					assertThat(e.getDetails()).containsExactly(
							"rows[0].customer.gender: expected a STRING value",
							"rows[1].customer.gender: expected a STRING value",
							"rows[2].customer.tenureMonths: expected a NUMBER value",
							"rows[3].customer.tenureMonths: expected a NUMBER value");
					assertThat(e.getMessage()).doesNotContain("secret");
				});
	}

	@Test
	void userKeysAndStringValuesAreStrippedOnTheWayInAndKeysOnTheWayOut() {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad load = load(tenant, row(" u-1 ", " GOLD "));
		TenantContext.callAs(tenant, () -> service.activate(load.getId()));

		assertThat(TenantContext.callAs(tenant, () -> service.find("u-1")).orElseThrow().value("customer.segment"))
				.contains("GOLD");
		assertThat(TenantContext.callAs(tenant, () -> service.find("  u-1"))).isPresent();
	}

	@Test
	void oversizedBatchIsRejectedBeforeAnyWork() {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad load = TenantContext.callAs(tenant, () -> service.beginLoad());
		List<SnapshotRow> batch = IntStream.rangeClosed(0, AttributeSnapshotService.MAX_BATCH_ROWS)
				.mapToObj(i -> row("u-" + i, "GOLD")).toList();

		assertRejected(tenant, () -> service.append(load.getId(), batch), "SNAPSHOT_ROWS_INVALID");
	}

	@Test
	void onlyALoadingLoadTakesRowsOrChangesState() {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad active = load(tenant, row("u-1", "GOLD"));
		TenantContext.callAs(tenant, () -> service.activate(active.getId()));
		SnapshotLoad failed = TenantContext.callAs(tenant, () -> service.beginLoad());
		TenantContext.callAs(tenant, () -> service.fail(failed.getId()));

		assertRejected(tenant, () -> service.append(active.getId(), List.of(row("u-2", "GOLD"))), "SNAPSHOT_LOAD_NOT_OPEN");
		assertRejected(tenant, () -> service.activate(active.getId()), "SNAPSHOT_LOAD_NOT_OPEN");
		assertRejected(tenant, () -> service.activate(failed.getId()), "SNAPSHOT_LOAD_NOT_OPEN");
		assertRejected(tenant, () -> service.fail(failed.getId()), "SNAPSHOT_LOAD_NOT_OPEN");
		assertThat(TenantContext.callAs(tenant, () -> service.activeLoad()).orElseThrow().getId())
				.isEqualTo(active.getId());
	}

	@Test
	void sensitiveValuesReachConditionsButNotScriptsOrStringForms() {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad load = load(tenant,
				new SnapshotRow("u-1", Map.of("customer.gender", "F", "customer.segment", "GOLD")));
		TenantContext.callAs(tenant, () -> service.activate(load.getId()));

		CustomerSnapshot snapshot = TenantContext.callAs(tenant, () -> service.find("u-1")).orElseThrow();

		assertThat(snapshot.value("customer.gender")).contains("F");
		assertThat(snapshot.scriptView()).containsOnly(Map.entry("customer.segment", "GOLD"));
		assertThat(snapshot.toString()).contains("customer.gender=***", "customer.segment=GOLD")
				.doesNotContain("=F").doesNotContain("u-1");
		assertThat(new SnapshotRow("u-1", Map.of("customer.gender", "F")).toString())
				.isEqualTo("SnapshotRow[attributes=[customer.gender]]");
	}

	@Test
	void flaggingAnAttributeSensitiveProtectsValuesAlreadyLoaded() {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad load = load(tenant, row("u-1", "GOLD"));
		TenantContext.callAs(tenant, () -> service.activate(load.getId()));
		AttributeDef segment = TenantContext.callAs(tenant, () -> catalog.list()).stream()
				.filter(def -> def.getName().equals("customer.segment")).findFirst().orElseThrow();

		TenantContext.callAs(tenant, () -> catalog.describe(segment.getId(), segment.getVersion(), true, null));

		CustomerSnapshot snapshot = TenantContext.callAs(tenant, () -> service.find("u-1")).orElseThrow();
		assertThat(snapshot.scriptView()).doesNotContainKey("customer.segment");
		assertThat(snapshot.toString()).contains("customer.segment=***");
	}

	@Test
	void concurrentActivationsLeaveExactlyOneLoadServing() throws Exception {
		for (boolean withServingLoad : new boolean[] {false, true}) {
			UUID tenant = tenantWithCatalog();
			if (withServingLoad) {
				SnapshotLoad serving = load(tenant, row("u-0", "GOLD"));
				TenantContext.callAs(tenant, () -> service.activate(serving.getId()));
			}
			List<UUID> candidates = IntStream.range(0, 5)
					.mapToObj(i -> load(tenant, row("u-" + i, "GOLD")).getId())
					.toList();
			List<Callable<SnapshotLoad>> activators = candidates.stream()
					.<Callable<SnapshotLoad>>map(id -> () -> TenantContext.callAs(tenant, () -> service.activate(id)))
					.toList();

			List<Throwable> failures = race(activators);

			// activations that happened not to overlap each supersede the previous winner
			int winners = candidates.size() - failures.size();
			List<SnapshotLoadStatus> statuses = TenantContext.callAs(tenant, () -> loads.findAllById(candidates))
					.stream().map(SnapshotLoad::getStatus).toList();
			assertThat(winners).as("withServingLoad=%s", withServingLoad).isPositive();
			assertThat(statuses).filteredOn(SnapshotLoadStatus.ACTIVE::equals).hasSize(1);
			assertThat(statuses).filteredOn(SnapshotLoadStatus.SUPERSEDED::equals).hasSize(winners - 1);
			assertThat(statuses).filteredOn(SnapshotLoadStatus.LOADING::equals).hasSize(failures.size());
			assertThat(failures).allSatisfy(e -> assertThat(e).isInstanceOfAny(
					ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class));
			assertThat(TenantContext.callAs(tenant, () -> loads.findAll()))
					.filteredOn(l -> l.getStatus() == SnapshotLoadStatus.ACTIVE).hasSize(1);
		}
	}

	@Test
	void anotherTenantCannotReadOrDriveTheLoads() {
		UUID owner = tenantWithCatalog();
		UUID other = tenantWithCatalog();
		SnapshotLoad ownerLoad = load(owner, row("u-1", "GOLD"));
		TenantContext.callAs(owner, () -> service.activate(ownerLoad.getId()));
		SnapshotLoad pending = TenantContext.callAs(owner, () -> service.beginLoad());

		assertThat(TenantContext.callAs(other, () -> service.find("u-1"))).isEmpty();
		assertThat(TenantContext.callAs(other, () -> service.activeLoad())).isEmpty();
		assertRejected(other, () -> service.append(pending.getId(), List.of(row("u-2", "GOLD"))),
				"SNAPSHOT_LOAD_NOT_FOUND");
		assertRejected(other, () -> service.activate(pending.getId()), "SNAPSHOT_LOAD_NOT_FOUND");
		// one ACTIVE load per tenant, not per table
		SnapshotLoad otherLoad = load(other, row("u-1", "SILVER"));
		TenantContext.callAs(other, () -> service.activate(otherLoad.getId()));

		assertThat(TenantContext.callAs(owner, () -> service.find("u-1")).orElseThrow().value("customer.segment"))
				.contains("GOLD");
		assertThat(TenantContext.callAs(other, () -> service.find("u-1")).orElseThrow().value("customer.segment"))
				.contains("SILVER");
	}

	private UUID tenantWithCatalog() {
		UUID tenant = newTenant(tenants);
		TenantContext.callAs(tenant, () -> {
			catalog.define("customer.segment", AttributeSource.SNAPSHOT, AttributeDataType.STRING, false, null);
			catalog.define("customer.gender", AttributeSource.SNAPSHOT, AttributeDataType.STRING, true, null);
			catalog.define("customer.tenureMonths", AttributeSource.SNAPSHOT, AttributeDataType.NUMBER, false, null);
			catalog.define("customer.employee", AttributeSource.SNAPSHOT, AttributeDataType.BOOLEAN, false, null);
			catalog.define("customer.registeredOn", AttributeSource.SNAPSHOT, AttributeDataType.DATE, false, null);
			catalog.define("customer.lastLogin", AttributeSource.SNAPSHOT, AttributeDataType.DATETIME, false, null);
			return catalog.define("amount", AttributeSource.REQUEST, AttributeDataType.NUMBER, false, null);
		});
		return tenant;
	}

	private SnapshotLoad load(UUID tenant, SnapshotRow... rows) {
		SnapshotLoad load = TenantContext.callAs(tenant, () -> service.beginLoad());
		return TenantContext.callAs(tenant, () -> service.append(load.getId(), List.of(rows)));
	}

	private static SnapshotRow row(String userKey, String segment) {
		return new SnapshotRow(userKey, Map.of("customer.segment", segment));
	}
}
