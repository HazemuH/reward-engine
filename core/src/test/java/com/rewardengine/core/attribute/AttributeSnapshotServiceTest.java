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
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager txManager;

	@Autowired
	SnapshotBatches batches;

	@Test
	void loadIsNotServedUntilActivatedThenValuesComeBackTyped() {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad load = TenantContext.callAs(tenant, () -> service.beginLoad());
		TenantContext.callAs(tenant, () -> service.append(load.getId(), newBatchKey(), List.of(new SnapshotRow("u-1", Map.of(
				"customer.segment", "GOLD",
				"customer.tenureMonths", 18,
				"customer.employee", true,
				"customer.registeredOn", LocalDate.of(2024, 2, 29),
				"customer.lastLogin", LocalDateTime.of(2026, 10, 1, 8, 30))))));

		assertThat(TenantContext.callAs(tenant, () -> service.find("u-1"))).isEmpty();

		SnapshotLoad active = TenantContext.callAs(tenant, () -> service.activate(load.getId(), service.rowCount(load.getId())));
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
		TenantContext.callAs(tenant, () -> service.activate(first.getId(), service.rowCount(first.getId())));
		SnapshotLoad second = load(tenant, row("u-1", "PLATINUM"));

		TenantContext.callAs(tenant, () -> service.activate(second.getId(), service.rowCount(second.getId())));

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
		TenantContext.callAs(tenant, () -> service.activate(load.getId(), service.rowCount(load.getId())));

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
				row("u-0", "SILVER"),
				null);

		assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> service.append(load.getId(), newBatchKey(), batch)))
				.isInstanceOfSatisfying(BusinessRuleException.class, e -> {
					assertThat(e.getCode()).isEqualTo("SNAPSHOT_ROWS_INVALID");
					assertThat(e.getDetails()).containsExactlyInAnyOrder(
							"rows[0].customer.gender: expected a STRING of at most 1000 characters",
							"rows[0].amount: not a SNAPSHOT attribute in the catalog",
							"rows[0].customer.unknown: not a SNAPSHOT attribute in the catalog",
							"rows[1].userKey: required, at most 200 storable characters",
							"rows[3].userKey: same customer as rows[2]",
							"rows[5]: must not be null");
					assertThat(e.getMessage()).doesNotContain("42").doesNotContain("u-2");
				});
		assertThat(TenantContext.callAs(tenant, () -> service.activate(load.getId(), service.rowCount(load.getId()))).getRowCount()).isOne();
	}

	@Test
	void valuesTheDatabaseCouldNotStoreAreRejectedWithoutEchoingThem() {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad load = TenantContext.callAs(tenant, () -> service.beginLoad());
		List<SnapshotRow> batch = List.of(
				new SnapshotRow("u-1", Map.of("customer.gender", "F\u0000secret")),
				new SnapshotRow("u-2", Map.of("customer.gender", "\uD83D")),
				new SnapshotRow("u-3", Map.of("customer.tenureMonths", new BigDecimal("1E+1500"))),
				new SnapshotRow("u-4", Map.of("customer.tenureMonths", new BigDecimal("0.000000000000000000001"))),
				new SnapshotRow("u-5", Map.of("customer.segment", "x".repeat(1_001))),
				new SnapshotRow("u-6", Map.of("bad key\nINFO forged log line", "x")));

		assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> service.append(load.getId(), newBatchKey(), batch)))
				.isInstanceOfSatisfying(BusinessRuleException.class, e -> {
					assertThat(e.getDetails()).containsExactly(
							"rows[0].customer.gender: expected a STRING of at most 1000 characters",
							"rows[1].customer.gender: expected a STRING of at most 1000 characters",
							"rows[2].customer.tenureMonths: expected a NUMBER with at most 30 integer and 20 fraction digits",
							"rows[3].customer.tenureMonths: expected a NUMBER with at most 30 integer and 20 fraction digits",
							"rows[4].customer.segment: expected a STRING of at most 1000 characters",
							"rows[5]: an attribute key that is not a valid attribute name");
					assertThat(e.getMessage()).doesNotContain("secret").doesNotContain("forged");
				});
	}

	@Test
	void unstorableUserKeyIsAValidationErrorAndNeverFound() {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad load = load(tenant, row("u-1", "GOLD"));
		TenantContext.callAs(tenant, () -> service.activate(load.getId(), service.rowCount(load.getId())));
		SnapshotLoad next = TenantContext.callAs(tenant, () -> service.beginLoad());

		assertThatThrownBy(() -> TenantContext.callAs(tenant,
				() -> service.append(next.getId(), newBatchKey(), List.of(row("u-1\u0000", "GOLD")))))
				.isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getDetails())
						.containsExactly("rows[0].userKey: required, at most 200 storable characters"));
		assertThat(TenantContext.callAs(tenant, () -> service.find("u-1\u0000"))).isEmpty();
	}

	@Test
	void emptyLoadCannotReplaceTheServingOne() {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad serving = load(tenant, row("u-1", "GOLD"));
		TenantContext.callAs(tenant, () -> service.activate(serving.getId(), service.rowCount(serving.getId())));
		SnapshotLoad empty = TenantContext.callAs(tenant, () -> service.beginLoad());

		assertRejected(tenant, () -> service.activate(empty.getId(), service.rowCount(empty.getId())), "SNAPSHOT_LOAD_EMPTY");
		assertThat(TenantContext.callAs(tenant, () -> service.find("u-1"))).isPresent();
	}

	@Test
	void parallelAppendsOfDifferentCustomersToOneLoadAllLand() throws Exception {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad load = TenantContext.callAs(tenant, () -> service.beginLoad());
		List<Callable<SnapshotLoad>> appenders = IntStream.range(0, 8)
				.<Callable<SnapshotLoad>>mapToObj(i -> () -> TenantContext.callAs(tenant, () -> service.append(
						load.getId(), newBatchKey(), List.of(row("a-" + i, "GOLD"), row("b-" + i, "SILVER")))))
				.toList();

		assertThat(race(appenders)).isEmpty();
		assertThat(TenantContext.callAs(tenant, () -> service.activate(load.getId(), service.rowCount(load.getId()))).getRowCount()).isEqualTo(16);
	}

	@Test
	void appendRacingActivationNeverLeavesAnUncountedRowInAServingLoad() throws Exception {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad load = load(tenant, row("u-0", "GOLD"));
		List<Callable<Object>> callers = new ArrayList<>();
		callers.add(() -> TenantContext.callAs(tenant, () -> service.activate(load.getId(), 1)));
		IntStream.rangeClosed(1, 8).forEach(i -> callers.add(() -> TenantContext.callAs(tenant,
				() -> service.append(load.getId(), newBatchKey(), List.of(row("u-" + i, "GOLD"))))));

		List<Throwable> failures = race(callers);

		// appends that beat the activation make it INCOMPLETE; appends after it are NOT_OPEN
		assertThat(failures).allSatisfy(e -> assertThat(e).isInstanceOfSatisfying(BusinessRuleException.class,
				b -> assertThat(b.getCode()).isIn("SNAPSHOT_LOAD_NOT_OPEN", "SNAPSHOT_LOAD_INCOMPLETE")));
		long stored = TenantContext.callAs(tenant, () -> service.rowCount(load.getId()));
		Optional<SnapshotLoad> active = TenantContext.callAs(tenant, () -> service.activeLoad());
		if (active.isPresent()) {
			assertThat(active.get().getRowCount()).isEqualTo(stored).isOne();
		} else {
			assertThat(TenantContext.callAs(tenant, () -> loads.findById(load.getId())).orElseThrow().getStatus())
					.isEqualTo(SnapshotLoadStatus.LOADING);
		}
	}

	@Test
	void truncatedLoadCannotReplaceTheServingOne() {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad serving = load(tenant, row("u-1", "GOLD"), row("u-2", "GOLD"));
		TenantContext.callAs(tenant, () -> service.activate(serving.getId(), 2));
		SnapshotLoad partial = load(tenant, row("u-1", "SILVER"));

		assertRejected(tenant, () -> service.activate(partial.getId(), 2), "SNAPSHOT_LOAD_INCOMPLETE");
		assertThat(TenantContext.callAs(tenant, () -> service.find("u-2"))).isPresent();
		assertThat(TenantContext.callAs(tenant, () -> service.rowCount(partial.getId()))).isOne();
	}

	@Test
	void stringLimitCountsCharactersAfterStripping() {
		UUID tenant = tenantWithCatalog();
		String padded = "     " + "x".repeat(995) + "     ";
		String emoji = "\uD83D\uDE00".repeat(600);
		SnapshotLoad load = load(tenant, row("u-1", padded), row("u-2", emoji));
		TenantContext.callAs(tenant, () -> service.activate(load.getId(), service.rowCount(load.getId())));

		assertThat(TenantContext.callAs(tenant, () -> service.find("u-1")).orElseThrow().value("customer.segment"))
				.contains("x".repeat(995));
		assertThat(TenantContext.callAs(tenant, () -> service.find("u-2")).orElseThrow().value("customer.segment"))
				.contains(emoji);
	}

	@Test
	void databaseErrorsCarryNoRowValues() {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad load = load(tenant, row("secret-key", "GOLD"));
		String insert = "insert into attribute_snapshot (id, tenant_id, load_id, user_key, attributes, created_at) "
				+ "values (?, ?, ?, ?, cast(? as jsonb), now())";
		List<Object[]> duplicate = List.<Object[]>of(new Object[] {UUID.randomUUID(), tenant, load.getId(),
				"secret-key", "{\"customer.gender\": \"secret-value\"}"});

		// a failed batch is what Hibernate logs at ERROR; neither its message nor any cause may quote the row
		assertThatThrownBy(() -> jdbc.batchUpdate(insert, duplicate))
				.satisfies(e -> assertNoSecret(e));
	}

	@Test
	void userKeysAndStringValuesAreStrippedOnTheWayInAndKeysOnTheWayOut() {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad load = load(tenant, row(" u-1 ", " GOLD "));
		TenantContext.callAs(tenant, () -> service.activate(load.getId(), service.rowCount(load.getId())));

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

		assertRejected(tenant, () -> service.append(load.getId(), newBatchKey(), batch), "SNAPSHOT_ROWS_INVALID");
	}

	@Test
	void resendingABatchUnderItsKeyIsANoOpButADuplicateCustomerIsNot() {
		UUID tenant = tenantWithCatalog();
		List<SnapshotRow> rows = List.of(row("u-1", "GOLD"), row("u-2", "SILVER"));
		SnapshotLoad load = TenantContext.callAs(tenant, () -> service.beginLoad());
		TenantContext.callAs(tenant, () -> service.append(load.getId(), "batch-1", rows));

		TenantContext.callAs(tenant, () -> service.append(load.getId(), "batch-1", rows));
		assertThat(TenantContext.callAs(tenant, () -> service.rowCount(load.getId()))).isEqualTo(2);

		// the same customer again under another batch is a duplicate in the source, reported by row
		assertThatThrownBy(() -> TenantContext.callAs(tenant,
				() -> service.append(load.getId(), "batch-2", List.of(row("u-3", "GOLD"), row("u-1", "GOLD")))))
				.isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getDetails())
						.containsExactly("rows[1].userKey: customer already in this load"));
		assertRejected(tenant, () -> service.append(load.getId(), " ", rows), "SNAPSHOT_ROWS_INVALID");
		// the key resent with other content — other values for the same customers, too — is a loader bug
		for (List<SnapshotRow> other : List.of(List.of(row("u-9", "GOLD")),
				List.of(row("u-1", "PLATINUM"), row("u-2", "SILVER")))) {
			assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> service.append(load.getId(), "batch-1", other)))
					.isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getDetails()).containsExactly(
							"batchKey: batch-1 was already used for a different batch in this load"));
		}
		// an empty batch is recorded too: its key can't later carry rows
		TenantContext.callAs(tenant, () -> service.append(load.getId(), "batch-empty", List.of()));
		assertRejected(tenant, () -> service.append(load.getId(), "batch-empty", List.of(row("u-7", "GOLD"))),
				"SNAPSHOT_ROWS_INVALID");
	}

	@Test
	void resendingALandedBatchAfterActivationIsStillANoOp() {
		UUID tenant = tenantWithCatalog();
		List<SnapshotRow> rows = List.of(row("u-1", "GOLD"));
		SnapshotLoad load = TenantContext.callAs(tenant, () -> service.beginLoad());
		TenantContext.callAs(tenant, () -> service.append(load.getId(), "batch-1", rows));
		TenantContext.callAs(tenant, () -> service.activate(load.getId(), 1));

		assertThat(TenantContext.callAs(tenant, () -> service.append(load.getId(), "batch-1", rows)).getStatus())
				.isEqualTo(SnapshotLoadStatus.ACTIVE);
		assertRejected(tenant, () -> service.append(load.getId(), "batch-2", List.of(row("u-2", "GOLD"))),
				"SNAPSHOT_LOAD_NOT_OPEN");
	}

	@Test
	void resendingToAFailedLoadIsNotReportedAsSuccess() {
		UUID tenant = tenantWithCatalog();
		List<SnapshotRow> rows = List.of(row("u-1", "GOLD"));
		SnapshotLoad load = TenantContext.callAs(tenant, () -> service.beginLoad());
		TenantContext.callAs(tenant, () -> service.append(load.getId(), "batch-1", rows));
		TenantContext.callAs(tenant, () -> service.fail(load.getId()));

		assertRejected(tenant, () -> service.append(load.getId(), "batch-1", rows), "SNAPSHOT_LOAD_NOT_OPEN");
	}

	@Test
	void overlappingFailRetriesBothReturnTheFailedLoad() throws Exception {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad load = TenantContext.callAs(tenant, () -> service.beginLoad());
		List<Callable<SnapshotLoad>> failers = IntStream.range(0, 5)
				.<Callable<SnapshotLoad>>mapToObj(i -> () -> TenantContext.callAs(tenant, () -> service.fail(load.getId())))
				.toList();

		assertThat(race(failers)).isEmpty();
		assertThat(TenantContext.callAs(tenant, () -> loads.findById(load.getId())).orElseThrow().getStatus())
				.isEqualTo(SnapshotLoadStatus.FAILED);
	}

	@Test
	void retryOverlappingTheUncommittedOriginalWaitsThenIsANoOp() throws Exception {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad load = TenantContext.callAs(tenant, () -> service.beginLoad());
		List<SnapshotRow> rows = List.of(row("u-1", "GOLD"), row("u-2", "SILVER"));
		CountDownLatch appended = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		TransactionTemplate tx = new TransactionTemplate(txManager);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<?> original = executor.submit(() -> TenantContext.callAs(tenant, () -> tx.execute(status -> {
				service.append(load.getId(), "batch-1", rows);
				appended.countDown();
				await(release);
				return null;
			})));
			await(appended);
			Future<SnapshotLoad> retry = executor.submit(() -> TenantContext.callAs(tenant,
					() -> service.append(load.getId(), "batch-1", rows)));
			awaitLockWait("insert into snapshot_batch");
			release.countDown();
			original.get(10, TimeUnit.SECONDS);

			assertThat(retry.get(10, TimeUnit.SECONDS).getId()).isEqualTo(load.getId());
		}
		assertThat(TenantContext.callAs(tenant, () -> service.rowCount(load.getId()))).isEqualTo(2);
	}

	@Test
	void retriedActivationAndFailAreSafeButASupersededLoadIsNotReportedLive() {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad first = load(tenant, row("u-1", "GOLD"));
		SnapshotLoad active = TenantContext.callAs(tenant, () -> service.activate(first.getId(), 1));
		SnapshotLoad second = load(tenant, row("u-1", "SILVER"));
		TenantContext.callAs(tenant, () -> service.activate(second.getId(), 1));

		assertThat(TenantContext.callAs(tenant, () -> service.activate(second.getId(), 1)).getActivatedAt())
				.isNotNull();
		// it did activate once, but a retry must not report a replaced load as serving
		assertRejected(tenant, () -> service.activate(first.getId(), 1), "SNAPSHOT_LOAD_SUPERSEDED");
		assertThat(TenantContext.callAs(tenant, () -> loads.findById(first.getId())).orElseThrow().getActivatedAt())
				.isEqualTo(active.getActivatedAt());
		assertThat(TenantContext.callAs(tenant, () -> service.activeLoad()).orElseThrow().getId())
				.isEqualTo(second.getId());
		// a retry that disagrees on the count is not a retry
		assertRejected(tenant, () -> service.activate(first.getId(), 2), "SNAPSHOT_LOAD_INCOMPLETE");
		SnapshotLoad failed = TenantContext.callAs(tenant, () -> service.beginLoad());
		TenantContext.callAs(tenant, () -> service.fail(failed.getId()));
		assertThat(TenantContext.callAs(tenant, () -> service.fail(failed.getId())).getStatus())
				.isEqualTo(SnapshotLoadStatus.FAILED);
	}

	@Test
	void concurrentAppendOfTheSameCustomerIsReportedAsABatchProblem() throws Exception {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad load = TenantContext.callAs(tenant, () -> service.beginLoad());
		CountDownLatch appended = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		TransactionTemplate tx = new TransactionTemplate(txManager);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			// first append holds its transaction open, so the second's duplicate-key pre-check can't see it
			Future<?> first = executor.submit(() -> TenantContext.callAs(tenant, () -> tx.execute(status -> {
				service.append(load.getId(), newBatchKey(), List.of(row("u-1", "GOLD")));
				appended.countDown();
				await(release);
				return null;
			})));
			await(appended);
			Future<SnapshotLoad> second = executor.submit(() -> TenantContext.callAs(tenant,
					() -> service.append(load.getId(), newBatchKey(), List.of(row("u-1", "SILVER"), row("u-2", "GOLD")))));
			awaitLockWait("insert into attribute_snapshot");
			release.countDown();
			first.get(10, TimeUnit.SECONDS);

			// through JDBC batching and logServerErrorDetail=false, the unique violation still maps
			assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
					.cause()
					.isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getDetails())
							.containsExactly("batch: rejected by the database; a customer in it was appended concurrently"));
		}
		assertThat(TenantContext.callAs(tenant, () -> service.rowCount(load.getId()))).isOne();
	}

	@Test
	void misspelledCaseIsPointedAtTheStoredName() {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad load = TenantContext.callAs(tenant, () -> service.beginLoad());

		assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> service.append(load.getId(),
				newBatchKey(), List.of(new SnapshotRow("u-1", Map.of("Customer.Segment", "GOLD"))))))
				.isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getDetails()).containsExactly(
						"rows[0].Customer.Segment: not a SNAPSHOT attribute in the catalog; it is spelled customer.segment"));
	}

	@Test
	void trailingZerosDoNotCountAgainstTheNumberBound() {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad load = load(tenant,
				new SnapshotRow("u-1", Map.of("customer.tenureMonths", new BigDecimal("1.000000000000000000000"))));
		TenantContext.callAs(tenant, () -> service.activate(load.getId(), 1));

		assertThat(TenantContext.callAs(tenant, () -> service.find("u-1")).orElseThrow().value("customer.tenureMonths"))
				.hasValueSatisfying(v -> assertThat((BigDecimal) v).isEqualByComparingTo("1"));
	}

	@Test
	void onlyALoadingLoadTakesRowsOrChangesState() {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad active = load(tenant, row("u-1", "GOLD"));
		TenantContext.callAs(tenant, () -> service.activate(active.getId(), service.rowCount(active.getId())));
		SnapshotLoad failed = TenantContext.callAs(tenant, () -> service.beginLoad());
		TenantContext.callAs(tenant, () -> service.fail(failed.getId()));

		assertRejected(tenant, () -> service.append(active.getId(), newBatchKey(), List.of(row("u-2", "GOLD"))), "SNAPSHOT_LOAD_NOT_OPEN");
		assertRejected(tenant, () -> service.activate(active.getId(), 2), "SNAPSHOT_LOAD_INCOMPLETE");
		assertRejected(tenant, () -> service.activate(failed.getId(), 1), "SNAPSHOT_LOAD_NOT_OPEN");
		assertRejected(tenant, () -> service.fail(active.getId()), "SNAPSHOT_LOAD_NOT_OPEN");
		assertThat(TenantContext.callAs(tenant, () -> service.activeLoad()).orElseThrow().getId())
				.isEqualTo(active.getId());
	}

	@Test
	void sensitiveValuesReachConditionsButNotScriptsOrStringForms() {
		UUID tenant = tenantWithCatalog();
		SnapshotLoad load = load(tenant,
				new SnapshotRow("u-1", Map.of("customer.gender", "F", "customer.segment", "GOLD")));
		TenantContext.callAs(tenant, () -> service.activate(load.getId(), service.rowCount(load.getId())));

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
		TenantContext.callAs(tenant, () -> service.activate(load.getId(), service.rowCount(load.getId())));
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
				TenantContext.callAs(tenant, () -> service.activate(serving.getId(), service.rowCount(serving.getId())));
			}
			List<UUID> candidates = IntStream.range(0, 5)
					.mapToObj(i -> load(tenant, row("u-" + i, "GOLD")).getId())
					.toList();
			List<Callable<SnapshotLoad>> activators = candidates.stream()
					.<Callable<SnapshotLoad>>map(id -> () -> TenantContext.callAs(tenant, () -> service.activate(id, 1)))
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
			assertThat(failures).allMatch(ObjectOptimisticLockingFailureException.class::isInstance);
			assertThat(TenantContext.callAs(tenant, () -> loads.findAll()))
					.filteredOn(l -> l.getStatus() == SnapshotLoadStatus.ACTIVE).hasSize(1);
		}
	}

	@Test
	void batchRecordsAreReadOnlyWithinTheirTenant() {
		UUID owner = tenantWithCatalog();
		UUID other = tenantWithCatalog();
		SnapshotLoad load = TenantContext.callAs(owner, () -> service.beginLoad());
		TenantContext.callAs(owner, () -> service.append(load.getId(), "batch-1", List.of(row("u-1", "GOLD"))));

		// native SQL: tenant_id is bound by hand, so prove it
		assertThat(TenantContext.callAs(owner, () -> batches.requestHash(load.getId(), "batch-1"))).isPresent();
		assertThat(TenantContext.callAs(other, () -> batches.requestHash(load.getId(), "batch-1"))).isEmpty();
	}

	@Test
	void anotherTenantCannotReadOrDriveTheLoads() {
		UUID owner = tenantWithCatalog();
		UUID other = tenantWithCatalog();
		SnapshotLoad ownerLoad = load(owner, row("u-1", "GOLD"));
		TenantContext.callAs(owner, () -> service.activate(ownerLoad.getId(), service.rowCount(ownerLoad.getId())));
		SnapshotLoad pending = TenantContext.callAs(owner, () -> service.beginLoad());

		assertThat(TenantContext.callAs(other, () -> service.find("u-1"))).isEmpty();
		assertThat(TenantContext.callAs(other, () -> service.activeLoad())).isEmpty();
		assertRejected(other, () -> service.append(pending.getId(), newBatchKey(), List.of(row("u-2", "GOLD"))),
				"SNAPSHOT_LOAD_NOT_FOUND");
		assertRejected(other, () -> service.activate(pending.getId(), service.rowCount(pending.getId())), "SNAPSHOT_LOAD_NOT_FOUND");
		// one ACTIVE load per tenant, not per table
		SnapshotLoad otherLoad = load(other, row("u-1", "SILVER"));
		TenantContext.callAs(other, () -> service.activate(otherLoad.getId(), service.rowCount(otherLoad.getId())));

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
		return TenantContext.callAs(tenant, () -> service.append(load.getId(), newBatchKey(), List.of(rows)));
	}

	/** Until a session running a statement starting with {@code statement} waits on a lock. */
	private void awaitLockWait(String statement) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock'"
				+ " and query like ? || '%'", Long.class, statement) == 0) {
			assertThat(System.nanoTime()).as("no session blocked in: " + statement).isLessThan(deadline);
			Thread.sleep(20);
		}
	}

	private static String newBatchKey() {
		return "b-" + UUID.randomUUID();
	}

	private static void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static SnapshotRow row(String userKey, String segment) {
		return new SnapshotRow(userKey, Map.of("customer.segment", segment));
	}

	/** Every message Hibernate's SqlExceptionHelper could log: the cause chain and each SQLException's next chain. */
	private static void assertNoSecret(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			assertThat(String.valueOf(t.getMessage())).doesNotContain("secret");
			if (t instanceof SQLException sql) {
				for (SQLException next = sql.getNextException(); next != null; next = next.getNextException()) {
					assertNoSecret(next);
				}
			}
		}
	}
}
