package dev.dtp.worker;

import dev.dtp.adapters.persistence.PostgresOutbox;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Test-only child JVM: parent forcibly terminates it after its claim commit. */
public final class LeaseCrashProbe {
    private LeaseCrashProbe() { }
    public static void main(String[] args) throws InterruptedException {
        var datasource = new DriverManagerDataSource(System.getenv("PROBE_JDBC_URL"), System.getenv("PROBE_JDBC_USER"), System.getenv("PROBE_JDBC_PASSWORD"));
        var outbox = new PostgresOutbox(new JdbcTemplate(datasource), new DataSourceTransactionManager(datasource));
        if (outbox.claim().isEmpty()) throw new IllegalStateException("Probe found no claimable event.");
        new java.util.concurrent.CountDownLatch(1).await();
    }
}
