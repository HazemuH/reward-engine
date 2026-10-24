package com.rewardengine.core.attribute;

import static com.rewardengine.core.TestSupport.assertRejected;
import static com.rewardengine.core.TestSupport.newTenant;
import static com.rewardengine.core.TestSupport.race;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rewardengine.common.error.BusinessRuleException;
import com.rewardengine.common.tenant.TenantContext;
import com.rewardengine.core.TestcontainersConfiguration;
import com.rewardengine.core.tenant.TenantRepository;
import java.util.List;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AttributeCatalogServiceTest {

	@Autowired
	TenantRepository tenants;

	@Autowired
	AttributeCatalogService catalog;

	@Autowired
	PlatformTransactionManager txManager;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void definedAttributesAreListedByName() {
		UUID tenant = newTenant(tenants);

		TenantContext.callAs(tenant, () -> catalog.define("customer.segment", AttributeSource.SNAPSHOT,
				AttributeDataType.STRING, false, "  Customer tier  "));
		TenantContext.callAs(tenant,
				() -> catalog.define("amount", AttributeSource.REQUEST, AttributeDataType.NUMBER, false, null));

		List<AttributeDef> listed = TenantContext.callAs(tenant, () -> catalog.list());
		assertThat(listed).extracting(AttributeDef::getName).containsExactly("amount", "customer.segment");
		assertThat(listed.get(1).getDescription()).isEqualTo("Customer tier");
		assertThat(listed.get(0).getDescription()).isNull();
	}

	@Test
	void invalidDefinitionIsRejectedWithEveryViolation() {
		UUID tenant = newTenant(tenants);

		assertThatThrownBy(() -> TenantContext.callAs(tenant,
				() -> catalog.define("1bad", null, null, false, "x".repeat(501))))
				.isInstanceOfSatisfying(BusinessRuleException.class, e -> {
					assertThat(e.getCode()).isEqualTo("ATTRIBUTE_INVALID");
					assertThat(e.getDetails()).containsExactly(
							"name: " + AttributeName.RULE,
							"source: required",
							"dataType: required",
							"description: at most 500 characters");
				});
		assertThat(TenantContext.callAs(tenant, () -> catalog.list())).isEmpty();
	}

	@Test
	void builtInNamesAreReservedAndDuplicatesRejected() {
		UUID tenant = newTenant(tenants);
		TenantContext.callAs(tenant,
				() -> catalog.define("amount", AttributeSource.REQUEST, AttributeDataType.NUMBER, false, null));

		assertRejected(tenant,
				() -> catalog.define("now", AttributeSource.REQUEST, AttributeDataType.DATETIME, false, null),
				"ATTRIBUTE_INVALID");
		assertRejected(tenant,
				() -> catalog.define("Now", AttributeSource.REQUEST, AttributeDataType.DATETIME, false, null),
				"ATTRIBUTE_INVALID");
		assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> catalog.define("customer.tier",
				AttributeSource.SNAPSHOT, AttributeDataType.STRING, false, "tier\u0000x")))
				.isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getDetails())
						.containsExactly("description: contains characters that can't be stored"));
		assertRejected(tenant,
				() -> catalog.define("amount", AttributeSource.SNAPSHOT, AttributeDataType.STRING, false, null),
				"ATTRIBUTE_ALREADY_DEFINED");
		assertRejected(tenant,
				() -> catalog.define("Amount", AttributeSource.SNAPSHOT, AttributeDataType.STRING, false, null),
				"ATTRIBUTE_ALREADY_DEFINED");
	}

	@Test
	void concurrentDefinesOfOneNameLeaveOneAndReportTheRestAsDuplicates() throws Exception {
		UUID tenant = newTenant(tenants);
		List<Callable<AttributeDef>> definers = IntStream.range(0, 5)
				.<Callable<AttributeDef>>mapToObj(i -> () -> TenantContext.callAs(tenant, () -> catalog.define(
						"customer.segment", AttributeSource.SNAPSHOT, AttributeDataType.STRING, false, null)))
				.toList();

		List<Throwable> failures = race(definers);

		assertThat(failures).hasSize(4).allSatisfy(e -> assertThat(e)
				.isInstanceOfSatisfying(BusinessRuleException.class,
						b -> assertThat(b.getCode()).isEqualTo("ATTRIBUTE_ALREADY_DEFINED")));
		assertThat(TenantContext.callAs(tenant, () -> catalog.list())).hasSize(1);
	}

	@Test
	void namesScriptsCouldNotAddressOrThatNestAreRejected() {
		UUID tenant = newTenant(tenants);
		TenantContext.callAs(tenant, () -> catalog.define("customer.segment", AttributeSource.SNAPSHOT,
				AttributeDataType.STRING, false, null));

		for (String name : List.of("customer.class", "x.metaClass", "x.MetaClass", "if", "x.static", "x.int",
				"x.yield", "now.offset", "Now.Offset")) {
			assertRejected(tenant, () -> catalog.define(name, AttributeSource.REQUEST, AttributeDataType.STRING,
					false, null), "ATTRIBUTE_INVALID");
		}
		// a value can't also be a namespace, in either direction (case-insensitively)
		assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> catalog.define("Customer",
				AttributeSource.REQUEST, AttributeDataType.STRING, false, null)))
				.isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getDetails()).singleElement()
						.asString().contains("nests with [customer.segment]"));
		assertRejected(tenant, () -> catalog.define("customer.segment.tier", AttributeSource.REQUEST,
				AttributeDataType.STRING, false, null), "ATTRIBUTE_INVALID");
		// '_' is not a wildcard: customer_x is unrelated to customer.segment
		assertThat(TenantContext.callAs(tenant, () -> catalog.define("customer_x", AttributeSource.REQUEST,
				AttributeDataType.STRING, false, null)).getName()).isEqualTo("customer_x");
	}

	@Test
	void overlappingDefinesCannotCreateAValueAndItsNamespace() throws Exception {
		UUID tenant = newTenant(tenants);
		CountDownLatch defined = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		TransactionTemplate tx = new TransactionTemplate(txManager);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			// the first define stays uncommitted, so without the catalog lock the second couldn't see it
			Future<?> first = executor.submit(() -> TenantContext.callAs(tenant, () -> tx.execute(status -> {
				catalog.define("customer", AttributeSource.REQUEST, AttributeDataType.STRING, false, null);
				defined.countDown();
				await(release);
				return null;
			})));
			await(defined);
			Future<AttributeDef> second = executor.submit(() -> TenantContext.callAs(tenant, () -> catalog.define(
					"customer.segment", AttributeSource.SNAPSHOT, AttributeDataType.STRING, false, null)));
			awaitLockWait("select 1 from (select pg_advisory_xact_lock");
			release.countDown();
			first.get(10, TimeUnit.SECONDS);

			assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS)).cause()
					.isInstanceOfSatisfying(BusinessRuleException.class,
							e -> assertThat(e.getCode()).isEqualTo("ATTRIBUTE_INVALID"));
		}
		assertThat(TenantContext.callAs(tenant, () -> catalog.list())).extracting(AttributeDef::getName)
				.containsExactly("customer");
	}

	@Test
	void catalogLockIsPerTenant() throws Exception {
		UUID busy = newTenant(tenants);
		UUID other = newTenant(tenants);
		CountDownLatch defined = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		TransactionTemplate tx = new TransactionTemplate(txManager);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			// one tenant holds its catalog lock in an open transaction...
			Future<?> holder = executor.submit(() -> TenantContext.callAs(busy, () -> tx.execute(status -> {
				catalog.define("amount", AttributeSource.REQUEST, AttributeDataType.NUMBER, false, null);
				defined.countDown();
				await(release);
				return null;
			})));
			await(defined);
			// ...and another tenant's define doesn't wait for it
			Future<AttributeDef> elsewhere = executor.submit(() -> TenantContext.callAs(other,
					() -> catalog.define("amount", AttributeSource.REQUEST, AttributeDataType.NUMBER, false, null)));
			assertThat(elsewhere.get(5, TimeUnit.SECONDS).getName()).isEqualTo("amount");
			release.countDown();
			holder.get(10, TimeUnit.SECONDS);
		}
	}

	@Test
	void describeChangesOnlyFlagAndDescriptionUnderOptimisticLock() {
		UUID tenant = newTenant(tenants);
		AttributeDef def = TenantContext.callAs(tenant, () -> catalog.define("customer.gender",
				AttributeSource.SNAPSHOT, AttributeDataType.STRING, false, null));

		AttributeDef described = TenantContext.callAs(tenant,
				() -> catalog.describe(def.getId(), def.getVersion(), true, "Protected characteristic"));

		assertThat(described.isSensitive()).isTrue();
		assertThat(described.getDescription()).isEqualTo("Protected characteristic");
		assertThat(described.getVersion()).isEqualTo(def.getVersion() + 1);
		assertThat(described.getDataType()).isEqualTo(AttributeDataType.STRING);
		assertThatThrownBy(() -> TenantContext.callAs(tenant,
				() -> catalog.describe(def.getId(), def.getVersion(), false, null)))
				.isInstanceOf(ObjectOptimisticLockingFailureException.class);
	}

	@Test
	void requireDefinedAcceptsCatalogAndBuiltInNamesAndListsTheRest() {
		UUID tenant = newTenant(tenants);
		TenantContext.callAs(tenant,
				() -> catalog.define("amount", AttributeSource.REQUEST, AttributeDataType.NUMBER, false, null));

		assertThatCode(() -> TenantContext.callAs(tenant, () -> {
			catalog.requireDefined(List.of("amount", "now"));
			return null;
		})).doesNotThrowAnyException();
		assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> {
			catalog.requireDefined(List.of("zone", "amount", "customer.tier", "now"));
			return null;
		})).isInstanceOfSatisfying(BusinessRuleException.class, e -> {
			assertThat(e.getCode()).isEqualTo("ATTRIBUTE_NOT_DEFINED");
			assertThat(e.getDetails()).containsExactly(
					"customer.tier: not in the attribute catalog", "zone: not in the attribute catalog");
		});
		assertThatThrownBy(() -> TenantContext.callAs(tenant, () -> {
			catalog.requireDefined(List.of("Amount", "Now"));
			return null;
		})).isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getDetails()).containsExactly(
				"Amount: not in the attribute catalog; it is spelled amount",
				"Now: not in the attribute catalog; it is spelled now"));
	}

	@Test
	void eachTenantHasItsOwnCatalog() {
		UUID owner = newTenant(tenants);
		UUID other = newTenant(tenants);
		AttributeDef def = TenantContext.callAs(owner,
				() -> catalog.define("amount", AttributeSource.REQUEST, AttributeDataType.NUMBER, false, null));

		assertThat(TenantContext.callAs(other, () -> catalog.list())).isEmpty();
		assertRejected(other, () -> catalog.get(def.getId()), "ATTRIBUTE_NOT_FOUND");
		assertRejected(other, () -> catalog.describe(def.getId(), def.getVersion(), true, null), "ATTRIBUTE_NOT_FOUND");
		assertRejected(other, () -> {
			catalog.requireDefined(List.of("amount"));
			return null;
		}, "ATTRIBUTE_NOT_DEFINED");
		// the same name is free in another tenant
		assertThat(TenantContext.callAs(other, () -> catalog.define("amount", AttributeSource.SNAPSHOT,
				AttributeDataType.STRING, false, null)).getSource()).isEqualTo(AttributeSource.SNAPSHOT);
		assertThat(TenantContext.callAs(owner, () -> catalog.get(def.getId())).isSensitive()).isFalse();
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

	private static void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}
}
