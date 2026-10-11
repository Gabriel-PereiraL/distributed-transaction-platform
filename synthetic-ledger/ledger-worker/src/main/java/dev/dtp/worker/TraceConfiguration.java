package dev.dtp.worker;

import dev.dtp.application.TraceContextSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TraceConfiguration {
    @Bean TraceContextSource currentTraceContext() { return KafkaTracing::currentTraceparent; }
}
