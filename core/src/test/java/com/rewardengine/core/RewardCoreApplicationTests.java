package com.rewardengine.core;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Boots the full context against a Flyway-migrated Postgres with
 * ddl-auto=validate, so any drift between entities and migrations fails here
 * instead of at deploy time.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RewardCoreApplicationTests {

	@Test
	void contextLoadsAgainstMigratedSchema() {
	}
}
