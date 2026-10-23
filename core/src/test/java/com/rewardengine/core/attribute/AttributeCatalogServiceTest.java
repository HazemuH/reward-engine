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
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AttributeCatalogServiceTest {

	@Autowired
	TenantRepository tenants;

	@Autowired
	AttributeCatalogService catalog;

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
}
