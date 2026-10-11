package dev.dtp.activity;

import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ActivityApplication {
    public static void main(String[] args) {
        if (args.length > 0 && "rebuild".equals(args[0])) {
            if (args.length != 2 || !"true".equals(System.getenv("DTP_ALLOW_REBUILD"))) throw new IllegalStateException("Explicit rebuild gate and new target prefix required.");
            String live = System.getenv().getOrDefault("DTP_ACTIVITY_PREFIX", "activity_v1");
            if (!args[1].matches("[A-Za-z][A-Za-z0-9_]{0,31}") || args[1].equals(live)) throw new IllegalArgumentException("Rebuild target must differ from live prefix.");
            var context = SpringApplication.run(ActivityApplication.class, "--dtp.activity.prefix=" + args[1],
                    "--dtp.activity.group=activity-rebuild-" + UUID.randomUUID(), "--spring.main.web-application-type=none", "--dtp.activity.listener-enabled=false");
            var mongo = context.getBean(org.springframework.data.mongodb.core.MongoTemplate.class);
            if (mongo.getCollection(args[1] + "_events").countDocuments() != 0 || mongo.getCollection(args[1] + "_snapshots").countDocuments() != 0) {
                context.close(); throw new IllegalStateException("Rebuild requires an empty target before consumption starts.");
            }
            context.getBean(org.springframework.kafka.config.KafkaListenerEndpointRegistry.class).getListenerContainer("activity").start();
            return;
        }
        SpringApplication.run(ActivityApplication.class, args);
    }
}
