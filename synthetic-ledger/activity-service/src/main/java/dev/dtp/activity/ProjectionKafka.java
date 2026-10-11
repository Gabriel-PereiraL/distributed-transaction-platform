package dev.dtp.activity;

import dev.dtp.contracts.InvalidContract;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration(proxyBeanMethods = false)
public class ProjectionKafka {
    @Bean("quarantineScheduler") org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler quarantineScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("dlt-resume-"); return scheduler;
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "dtp.kafka.create-topics", havingValue = "true", matchIfMissing = true)
    NewTopic dltTopic(@Value("${dtp.kafka.dlt-topic}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).config("retention.ms", "604800000").build();
    }
    @Bean ConcurrentKafkaListenerContainerFactory<String, byte[]> activityContainerFactory(ConsumerFactory<String, byte[]> consumers, ProjectionQuarantine quarantine,
            org.springframework.beans.factory.ObjectProvider<org.springframework.kafka.config.KafkaListenerEndpointRegistry> registry,
            @org.springframework.beans.factory.annotation.Qualifier("quarantineScheduler") org.springframework.scheduling.TaskScheduler scheduler,
            io.micrometer.core.instrument.MeterRegistry metrics) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, byte[]>();
        factory.setConsumerFactory(consumers);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setSyncCommits(true);
        factory.setConcurrency(1);
        var backoff = new ExponentialBackOffWithMaxRetries(2);
        backoff.setInitialInterval(250); backoff.setMultiplier(2.0); backoff.setMaxInterval(1000);
        var pauses = new org.springframework.kafka.listener.ListenerContainerPauseService(null, scheduler);
        var errors = new DefaultErrorHandler((record, error) -> {
            try { quarantine.recover(record, error); }
            catch (RuntimeException unconfirmed) {
                var container = registry.getObject().getListenerContainer("activity");
                if (container != null) pauses.pausePartition(container, new org.apache.kafka.common.TopicPartition(record.topic(), record.partition()),
                        java.time.Duration.ofMillis(1000 + java.util.concurrent.ThreadLocalRandom.current().nextLong(501)));
                metrics.counter("dtp.activity.quarantine.recovery_pauses").increment();
                throw unconfirmed;
            }
        }, backoff);
        errors.setBackOffFunction((record, error) -> () -> {
            var sequence = backoff.start();
            return () -> {
                long delay = sequence.nextBackOff();
                return delay == org.springframework.util.backoff.BackOffExecution.STOP ? delay
                        : delay + java.util.concurrent.ThreadLocalRandom.current().nextLong(126);
            };
        });
        errors.addNotRetryableExceptions(InvalidContract.class);
        errors.setResetStateOnRecoveryFailure(false);
        factory.setCommonErrorHandler(errors);
        return factory;
    }
}
