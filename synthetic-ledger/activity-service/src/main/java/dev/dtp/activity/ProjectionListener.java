package dev.dtp.activity;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class ProjectionListener {
    private final ProjectActivity projector;
    private final MeterRegistry metrics;
    private final ActivityTracing tracing;
    ProjectionListener(ProjectActivity projector, MeterRegistry metrics, ActivityTracing tracing) {
        this.projector = projector; this.metrics = metrics; this.tracing = tracing;
    }
    @KafkaListener(id = "activity", topics = "${dtp.kafka.events-topic}", groupId = "${dtp.activity.group}",
            autoStartup = "${dtp.activity.listener-enabled:true}", containerFactory = "activityContainerFactory")
    void project(ConsumerRecord<String, byte[]> record) {
        tracing.consume(record.headers(), () -> {
            projector.execute(record.value(), record.key());
            metrics.counter("dtp.activity.records.completed").increment();
        });
    }
}
