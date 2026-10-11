package dev.dtp.api;

import dev.dtp.adapters.legacy.DirectoryHttp;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "dtp.legacy.enabled", havingValue = "true")
class LegacyConfiguration {
    @Bean(destroyMethod = "close") DirectoryHttp legacyDirectory(@Value("${dtp.legacy.origin}") URI origin,
            @Value("${dtp.legacy.deadline-ms:500}") long deadline, @Value("${dtp.legacy.concurrency:4}") int concurrency) {
        return new DirectoryHttp(origin, Duration.ofMillis(deadline), concurrency);
    }
}
