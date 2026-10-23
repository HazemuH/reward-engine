package com.rewardengine.core.counter;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rewardengine.core.TestcontainersConfiguration;
import com.rewardengine.core.campaign.VersionCarryOver;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CounterDefCarryOverTest {

	@Autowired
	VersionCarryOver carryOver;

	@Autowired
	PlatformTransactionManager txManager;

	@Test
	void refusesToRunOutsideATransaction() {
		assertThatThrownBy(() -> carryOver.carryOver(UUID.randomUUID(), UUID.randomUUID()))
				.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void refusesToJoinAReadOnlyTransaction() {
		TransactionTemplate readOnly = new TransactionTemplate(txManager);
		readOnly.setReadOnly(true);

		assertThatThrownBy(() -> readOnly.executeWithoutResult(
				tx -> carryOver.carryOver(UUID.randomUUID(), UUID.randomUUID())))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("read-write");
	}
}
