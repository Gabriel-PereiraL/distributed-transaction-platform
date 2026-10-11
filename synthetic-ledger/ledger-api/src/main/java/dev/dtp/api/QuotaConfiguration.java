package dev.dtp.api;

import dev.dtp.application.MutationQuota;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class QuotaConfiguration {
    /** Explicit laboratory/test switch; production-like local profile enables Redis by default. */
    @Bean @ConditionalOnProperty(name = "dtp.quota.enabled", havingValue = "false")
    MutationQuota disabledQuota() { return actor -> { }; }
}
