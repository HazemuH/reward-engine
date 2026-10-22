package com.rewardengine.core.config;

import com.rewardengine.common.tenant.TenantIdentifierResolver;
import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires TenantContext into Hibernate so every @TenantId entity is stamped and
 * filtered by the current tenant. Without this, @TenantId has nothing to read.
 */
@Configuration(proxyBeanMethods = false)
class TenancyConfig {

	@Bean
	HibernatePropertiesCustomizer tenantIdentifierResolver() {
		return properties -> properties.put(
				MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, new TenantIdentifierResolver());
	}
}
