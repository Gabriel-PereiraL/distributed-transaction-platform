package dev.dtp.worker;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;

/** Read-only, bounded readiness; liveness never depends on remote services. */
@Configuration(proxyBeanMethods = false)
@Profile("container")
public class PostingProbeConfiguration {
    @Bean(destroyMethod = "close") Admin postingProbeAdmin(KafkaAdmin kafka) {
        var configuration = new HashMap<>(kafka.getConfigurationProperties());
        configuration.put("default.api.timeout.ms", "1000"); configuration.put("request.timeout.ms", "1000");
        return Admin.create(configuration);
    }
    @Bean HealthIndicator postingHealthIndicator(Admin postingProbeAdmin, KafkaListenerEndpointRegistry registry) {
        return () -> {
            var listener = registry.getListenerContainer("posting");
            if (listener == null || !listener.isRunning() || listener.getAssignedPartitions() == null || listener.getAssignedPartitions().isEmpty()) {
                return Health.down().build();
            }
            try {
                return postingProbeAdmin.describeCluster().nodes().get(2, TimeUnit.SECONDS).isEmpty() ? Health.down().build() : Health.up().build();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); return Health.down().build();
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unavailable) {
                return Health.down().build();
            }
        };
    }
}
