package com.rewardengine.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * reward-core: the engine. Owns the rule engine, campaigns, counters,
 * redemptions and referral — everything that writes reward state.
 *
 * No auth lives here (see docs/decisions/open-decisions.md #2). Today this
 * app trusts whatever sits in front of it on the network; before anything
 * beyond local development, put the API-key/mTLS check from
 * docs/architecture/overview.md section 18 in front of /internal/**, and the
 * reward-gateway module in front of /admin/**.
 */
@EnableScheduling
@EnableJpaAuditing
@ConfigurationPropertiesScan
@SpringBootApplication
public class RewardCoreApplication {

	public static void main(String[] args) {
		SpringApplication.run(RewardCoreApplication.class, args);
	}
}
