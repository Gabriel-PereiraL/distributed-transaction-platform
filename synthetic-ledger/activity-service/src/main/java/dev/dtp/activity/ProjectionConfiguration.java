package dev.dtp.activity;

import dev.dtp.contracts.TransferEventDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class ProjectionConfiguration {
    @Bean TransferEventDecoder decoder(ObjectMapper json) { return new TransferEventDecoder(json); }
    @Bean String activityGroupBoundary(@org.springframework.beans.factory.annotation.Value("${dtp.activity.group}") String group) {
        if (!group.matches("activity-[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("Activity group must use its own activity- namespace.");
        return group;
    }
    @Bean org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer mongoBudgets() {
        return settings -> settings.applyToClusterSettings(c -> c.serverSelectionTimeout(3, java.util.concurrent.TimeUnit.SECONDS))
                .applyToSocketSettings(s -> s.connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS).readTimeout(3, java.util.concurrent.TimeUnit.SECONDS));
    }
}
