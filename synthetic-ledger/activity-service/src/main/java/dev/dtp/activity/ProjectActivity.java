package dev.dtp.activity;

import dev.dtp.contracts.TransferEventDecoder;
import org.springframework.stereotype.Component;

@Component
public class ProjectActivity {
    private final TransferEventDecoder decoder;
    private final MongoHistory history;
    private final MongoSnapshots snapshots;
    public ProjectActivity(TransferEventDecoder decoder, MongoHistory history, MongoSnapshots snapshots) {
        this.decoder = decoder; this.history = history; this.snapshots = snapshots;
    }
    public void execute(byte[] bytes, String key) {
        var fact = decoder.decode(bytes, key);
        history.put(fact);
        snapshots.advance(fact);
    }
}
