package dev.dtp.worker;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PostingListener {
    private final KafkaRecordProcessor processor;
    private final MeterRegistry metrics;
    public PostingListener(KafkaRecordProcessor processor, MeterRegistry metrics) { this.processor = processor; this.metrics = metrics; }
    @KafkaListener(id = "posting", topics = "${dtp.kafka.events-topic}", groupId = "${dtp.kafka.posting-group}",
            autoStartup = "${dtp.consumer.enabled:true}", containerFactory = "postingContainerFactory")
    public void onRecord(ConsumerRecord<String, byte[]> record) {
        processor.handle(record);
        metrics.counter("dtp.posting.records.completed").increment();
    }
}
