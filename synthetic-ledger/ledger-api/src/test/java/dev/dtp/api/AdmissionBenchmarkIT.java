package dev.dtp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** Opt-in, fresh-container benchmark; excluded from the integration regression profile. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AdmissionBenchmarkIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94");
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka@sha256:9516fb7634bad307d17c33b589fde9023003b0cb761374f500002b980a3149b9");
    @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo@sha256:d0d926f94df099bff534b7ee5b5986458131a22489dfff8664509af0c1e2ca9c");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499").withExposedPorts(6379);
    static final TestIssuer ISSUER = new TestIssuer();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername); r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.enabled", () -> true); r.add("spring.data.redis.host", REDIS::getHost); r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER::url); r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> ISSUER.url() + "/jwks");
        r.add("dtp.quota.enabled", () -> true); r.add("dtp.quota.limit", () -> 100000); r.add("dtp.quota.window-ms", () -> 60000);
        r.add("management.tracing.sampling.probability", () -> 0.0);
    }
    @AfterAll static void closeIssuer() { ISSUER.close(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    final Path project = Path.of("..").toAbsolutePath().normalize();
    final Path artifacts = artifactDirectory();
    private static Path artifactDirectory() {
        String run = System.getProperty("dtp.benchmark.run", "current");
        if (!run.matches("[A-Za-z0-9-]{1,32}")) throw new IllegalArgumentException("Bounded benchmark run name required");
        return Path.of("target/f6-benchmark-" + run).toAbsolutePath();
    }
    final UUID tenant = UUID.nameUUIDFromBytes("DTP-F6-seed-1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    final List<Map<String, String>> pairs = new ArrayList<>();
    final List<Map<String, Object>> results = new ArrayList<>();

    @Test void repeatedAdmissionPostingAndProjectionBaselinePreservesFinancialInvariants() throws Exception {
        assertThat(System.getProperty("dtp.k6")).as("Explicit verified k6 executable required").isNotBlank();
        String mode = System.getProperty("dtp.benchmark.mode", "matched");
        assertThat(mode).as("Explicit bounded benchmark mode").isIn("matched", "recovery");
        Files.createDirectories(artifacts);
        seedOnlyDisposableDatabase();
        var beforePlan = plan();
        int activityPort;
        try (var socket = new ServerSocket(0)) { activityPort = socket.getLocalPort(); }
        var activity = child("activity-service", "activity", "--server.port=" + activityPort);
        var workers = new ArrayList<Process>();
        try (var mongo = com.mongodb.client.MongoClients.create(MONGO.getReplicaSetUrl())) {
            var snapshots = mongo.getDatabase("test").getCollection("f6_snapshots");
            var worker = child("ledger-worker", "worker-baseline", "--dtp.publisher.delay-ms=500"); workers.add(worker);
            await().atMost(Duration.ofSeconds(90)).until(() -> healthy(activityPort));
            workersReady(1, "baseline-ready");
            List<String> apis = List.of("http://127.0.0.1:" + port);
            config(apis, activityPort);
            runK6("warmup", "admission", "1", 4, true);
            drain(snapshots);
            try (var recording = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"))) {
                recording.disable("jdk.InitialEnvironmentVariable"); recording.disable("jdk.InitialSystemProperty");
                recording.disable("jdk.SystemProcess");
                recording.setMaxSize(64L * 1024 * 1024); recording.start();
                try {
                if (mode.equals("recovery")) {
                    recoverRamp(workers, apis, activityPort, snapshots);
                    return;
                }
                series("baseline-500ms", apis, activityPort, snapshots, "2", true);
                stop(worker); workers.clear();
                workers.add(child("ledger-worker", "worker-tuned", "--dtp.publisher.delay-ms=20"));
                workersReady(1, "tuned-ready");
                config(apis, activityPort); runK6("tuned-warmup", "admission", "1", 4, true); drain(snapshots);
                series("tuned-20ms", apis, activityPort, snapshots, "2", true);
                int other;
                try (var socket = new ServerSocket(0)) { other = socket.getLocalPort(); }
                var second = child("ledger-api", "api-second", "--server.port=" + other,
                        "--spring.flyway.enabled=false", "--dtp.quota.limit=100000");
                try {
                    await().atMost(Duration.ofSeconds(90)).until(() -> healthy(other));
                    workers.add(child("ledger-worker", "worker-second", "--dtp.publisher.delay-ms=20"));
                    workersReady(2, "two-workers-ready");
                    config(List.of(apis.getFirst(), "http://127.0.0.1:" + other), activityPort);
                    runK6("two-process-warmup", "admission", "1", 4, true); drain(snapshots);
                    series("two-api-two-worker", List.of(apis.getFirst(), "http://127.0.0.1:" + other), activityPort, snapshots, "2", true);
                } finally { stop(second); }
                } finally { recording.stop(); recording.dump(artifacts.resolve("api-profile.jfr")); }
            }
            invariants();
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("seed", "DTP-F6-seed-1"); evidence.put("customer_accounts", 100); evidence.put("synthetic_transfers", count("SELECT count(*) FROM transfers"));
            evidence.put("logical_processors", Runtime.getRuntime().availableProcessors());
            evidence.put("jvm", System.getProperty("java.version")); evidence.put("runs", results);
            evidence.put("query_plan_before", beforePlan); evidence.put("query_plan_after", plan());
            evidence.put("invariants", "zero holds/reservations, one transfer journal, balanced entries, materialized balance equals entries, all posted and projected");
            evidence.put("comparison", "Matched hot source, rate 2 per second, ten seconds, three repetitions; second API is a separate JVM. Ordered cases share warmed disposable data, not randomized independent trials. Prior 2/5/10 run exceeded baseline drain budget and is preserved separately.");
            evidence.put("limits", "Shared host; 100-account lab dataset, test RSA issuer, plaintext local broker; no production capacity/SLO claim. JFR covers first API JVM, not child API/workers. Generator CPU sampled separately; no CPU affinity/isolation.");
            Files.writeString(artifacts.resolve("baseline.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        } finally {
            for (var worker : workers) stop(worker);
            stop(activity);
            Files.deleteIfExists(artifacts.resolve("private-config.json"));
        }
    }
    private void recoverRamp(List<Process> workers, List<String> apis, int activityPort,
                             com.mongodb.client.MongoCollection<org.bson.Document> snapshots) throws Exception {
        runK6("baseline-ramp", "admission", "2,5,10", 10, true);
        long pending = count("SELECT count(*) FROM transfers WHERE status='ACCEPTED'");
        long unpublished = count("SELECT count(*) FROM outbox_deliveries WHERE status<>'PUBLISHED'");
        assertThat(pending).as("Overloaded baseline has an observable backlog").isPositive();
        Files.writeString(artifacts.resolve("recovery-before.json"), json.writeValueAsString(Map.of(
                "accepted_backlog", pending, "unpublished", unpublished, "publisher_delay_ms", 500)));
        long recoveryStarted = System.nanoTime();
        stop(workers.getFirst()); workers.clear();
        workers.add(child("ledger-worker", "worker-recovery", "--dtp.publisher.delay-ms=20"));
        workersReady(1, "recovery-ready");
        drain(snapshots); invariants();
        double seconds = (System.nanoTime() - recoveryStarted) / 1_000_000_000.0;
        config(apis, activityPort);
        runK6("recovery-query", "query", "10", 5, false);
        Files.writeString(artifacts.resolve("recovery.json"), json.writeValueAsString(Map.of(
                "synthetic_transfers", count("SELECT count(*) FROM transfers"), "backlog_after_load", pending,
                "recovery_seconds_including_worker_restart", seconds, "publisher_delay_ms_after_restart", 20,
                "financial_invariants", "passed", "projection", "all POSTED", "outbox", "all PUBLISHED",
                "scope", "Graceful worker restart with faster local publisher; no sustained-capacity or abrupt-crash claim")));
    }
    private void series(String label, List<String> apis, int activityPort, com.mongodb.client.MongoCollection<org.bson.Document> snapshots, String rates, boolean hot) throws Exception {
        for (int repetition = 0; repetition < 3; repetition++) {
            config(apis, activityPort);
            long before = count("SELECT count(*) FROM transfers");
            long started = System.nanoTime();
            try { runK6(label + "-" + repetition, "admission", rates, 10, hot); }
            catch (AssertionError unexpectedResponse) {
                drain(snapshots); invariants();
                Files.writeString(artifacts.resolve("failure-invariants.json"), json.writeValueAsString(Map.of(
                        "case", label, "repetition", repetition, "financial_invariants_after_failed_load", "passed")));
                throw unexpectedResponse;
            }
            long admissionsFinished = System.nanoTime();
            long backlog = count("SELECT count(*) FROM transfers WHERE status='ACCEPTED'");
            try { drain(snapshots); }
            catch (org.awaitility.core.ConditionTimeoutException incomplete) {
                Files.writeString(artifacts.resolve("incomplete-drain.json"), json.writeValueAsString(Map.of(
                        "case", label, "repetition", repetition, "not_posted", count("SELECT count(*) FROM transfers WHERE status<>'POSTED'"),
                        "unpublished", count("SELECT count(*) FROM outbox_deliveries WHERE status<>'PUBLISHED'"),
                        "full_financial_invariants", "not completed")));
                throw incomplete;
            }
            double drainSeconds = (System.nanoTime() - admissionsFinished) / 1_000_000_000.0;
            long admitted = count("SELECT count(*) FROM transfers") - before;
            double completedSeconds = (System.nanoTime() - started) / 1_000_000_000.0;
            invariants(); config(apis, activityPort);
            runK6(label + "-query-" + repetition, "query", "10", 5, false);
            results.add(Map.of("case", label, "repetition", repetition, "hot_source", hot, "admitted", admitted,
                    "backlog_after_load", backlog, "drain_seconds", drainSeconds, "completed_transfers_per_second", admitted / completedSeconds));
            Files.writeString(artifacts.resolve("partial-run-results.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(results));
        }
    }
    private void config(List<String> apis, int activityPort) throws Exception {
        var ids = jdbc.queryForList("SELECT id::text FROM transfers ORDER BY accepted_at DESC LIMIT 20", String.class);
        Files.writeString(artifacts.resolve("private-config.json"), json.writeValueAsString(Map.of("apis", apis, "activity", "http://127.0.0.1:" + activityPort,
                "pairs", pairs, "transfers", ids, "token", ISSUER.token(tenant, "alice", "f6-client", "dtp-ledger", "transfer:write transfer:read"),
                "activityToken", ISSUER.token(tenant, "alice", "f6-client", "dtp-activity", "activity:read"))));
    }
    private void runK6(String label, String mode, String rates, int seconds, boolean hot) throws Exception {
        var summary = artifacts.resolve(label + ".json");
        var builder = new ProcessBuilder(System.getProperty("dtp.k6"), "run", "--quiet", project.resolve("scripts/performance.js").toString());
        builder.environment().putAll(Map.of("BENCH_CONFIG", artifacts.resolve("private-config.json").toString(), "BENCH_MODE", mode,
                "BENCH_RATES", rates, "BENCH_SECONDS", Integer.toString(seconds), "BENCH_HOT", Boolean.toString(hot), "BENCH_LABEL", label, "BENCH_SUMMARY", summary.toString()));
        var process = builder.redirectErrorStream(true).redirectOutput(artifacts.resolve(label + ".log").toFile()).start();
        var observations = new ArrayList<Map<String, Object>>();
        var cpuObservations = new ArrayList<Map<String, Object>>();
        long lastWall = System.nanoTime();
        long lastCpu = process.info().totalCpuDuration().orElse(Duration.ZERO).toNanos();
        var operatingSystem = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        var observerProperties = new java.util.Properties();
        observerProperties.setProperty("user", POSTGRES.getUsername()); observerProperties.setProperty("password", POSTGRES.getPassword());
        observerProperties.setProperty("connectTimeout", "3"); observerProperties.setProperty("socketTimeout", "3");
        observerProperties.setProperty("ApplicationName", "f6-observer");
        try (var observer = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), observerProperties)) {
            long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            while (!process.waitFor(1, TimeUnit.SECONDS) && System.nanoTime() < deadline) {
                long wall = System.nanoTime();
                var cpu = process.info().totalCpuDuration();
                if (cpu.isPresent()) {
                    long nanos = cpu.orElseThrow().toNanos();
                    cpuObservations.add(Map.of("sample", cpuObservations.size(), "generator_cpu_cores", (double) (nanos - lastCpu) / (wall - lastWall),
                            "host_cpu_fraction", operatingSystem.getCpuLoad()));
                    lastWall = wall; lastCpu = nanos;
                }
                try (var statement = observer.createStatement()) {
                    statement.setQueryTimeout(1);
                    try (var rows = statement.executeQuery("""
                            SELECT coalesce(wait_event_type,'running'),coalesce(wait_event,'none'),count(*),
                             coalesce(max(extract(epoch FROM clock_timestamp()-xact_start)),0),
                             count(*) FILTER (WHERE cardinality(pg_blocking_pids(pid))>0)
                            FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND state='active'
                            GROUP BY wait_event_type,wait_event
                            """)) {
                        while (rows.next()) observations.add(Map.of("sample", observations.size(), "wait_type", rows.getString(1),
                                "wait_event", rows.getString(2), "sessions", rows.getLong(3), "max_transaction_seconds", rows.getDouble(4), "blocked_sessions", rows.getLong(5)));
                    }
                } catch (java.sql.SQLException unavailable) {
                    observations.add(Map.of("observer_error_type", unavailable.getClass().getSimpleName()));
                }
            }
            Files.writeString(artifacts.resolve(label + "-db-waits.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(observations));
            Files.writeString(artifacts.resolve(label + "-cpu.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(cpuObservations));
            assertThat(process.isAlive()).as("k6 bounded execution " + label).isFalse();
            assertThat(process.exitValue()).as("k6 status/financial admission gate " + label).isZero();
        } finally { if (process.isAlive()) stop(process); }
    }
    private Process child(String module, String label, String... args) throws Exception {
        var jar = project.resolve(module + "/target/" + module + "-0.1.0-SNAPSHOT.jar");
        assertThat(Files.isRegularFile(jar)).as("Package all executables before benchmark").isTrue();
        var javaName = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var command = new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"), "bin", javaName).toString(), "-Xmx256m", "-jar", jar.toString()));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command);
        builder.environment().putAll(Map.ofEntries(Map.entry("DTP_DATABASE_URL", POSTGRES.getJdbcUrl()), Map.entry("DTP_DATABASE_USER", POSTGRES.getUsername()),
                Map.entry("DTP_DATABASE_PASSWORD", POSTGRES.getPassword()), Map.entry("DTP_KAFKA_BOOTSTRAP_SERVERS", KAFKA.getBootstrapServers()),
                Map.entry("DTP_MONGO_URI", MONGO.getReplicaSetUrl()), Map.entry("DTP_ISSUER", ISSUER.url()), Map.entry("DTP_JWK_SET_URI", ISSUER.url() + "/jwks"),
                Map.entry("DTP_ACTIVITY_PREFIX", "f6"), Map.entry("DTP_ACTIVITY_GROUP", "activity-f6")));
        builder.environment().put("DTP_REDIS_HOST", REDIS.getHost());
        builder.environment().put("DTP_REDIS_PORT", REDIS.getMappedPort(6379).toString());
        return builder.redirectErrorStream(true).redirectOutput(artifacts.resolve(label + ".log").toFile()).start();
    }
    private void workersReady(int expectedMembers, String label) throws Exception {
        try (var admin = org.apache.kafka.clients.admin.AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "default.api.timeout.ms", "5000", "request.timeout.ms", "3000"))) {
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1))
                    .ignoreExceptionsMatching(error -> error instanceof java.util.concurrent.ExecutionException || error instanceof java.util.concurrent.TimeoutException)
                    .untilAsserted(() -> {
                var group = admin.describeConsumerGroups(List.of("ledger-posting-v1")).all().get(6, TimeUnit.SECONDS).get("ledger-posting-v1");
                assertThat(group.groupState()).isEqualTo(org.apache.kafka.common.GroupState.STABLE);
                assertThat(group.members()).hasSize(expectedMembers);
                assertThat(group.members()).allSatisfy(member -> assertThat(member.assignment().topicPartitions()).isNotEmpty());
                Files.writeString(artifacts.resolve(label + ".json"), json.writeValueAsString(Map.of("state", "STABLE", "members", expectedMembers,
                        "assigned_partitions", group.members().stream().map(member -> member.assignment().topicPartitions().size()).toList())));
            });
        }
    }
    private boolean healthy(int activityPort) {
        try { return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + activityPort + "/actuator/health/readiness")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200; }
        catch (Exception unavailable) { return false; }
    }
    private void drain(com.mongodb.client.MongoCollection<org.bson.Document> snapshots) {
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            assertThat(count("SELECT count(*) FROM transfers WHERE status<>'POSTED'")).isZero();
            assertThat(count("SELECT count(*) FROM outbox_deliveries WHERE status<>'PUBLISHED'")).isZero();
            assertThat(snapshots.countDocuments(new org.bson.Document("status", "POSTED"))).isEqualTo(count("SELECT count(*) FROM transfers"));
        });
    }
    private void invariants() {
        assertThat(count("SELECT count(*) FROM holds")).isZero();
        assertThat(count("SELECT count(*) FROM accounts WHERE reserved_cents<>0 OR (kind='CUSTOMER' AND balance_cents<0)")).isZero();
        assertThat(count("SELECT count(*) FROM (SELECT journal_id FROM journal_entries GROUP BY journal_id HAVING sum(signed_cents)<>0) invalid")).isZero();
        assertThat(count("SELECT count(*) FROM journals WHERE purpose='TRANSFER_POSTING'")).isEqualTo(count("SELECT count(*) FROM transfers"));
        assertThat(count("SELECT count(*) FROM accounts a WHERE balance_cents<>(SELECT coalesce(sum(signed_cents),0) FROM journal_entries e WHERE e.account_id=a.id)")).isZero();
    }
    private Object plan() {
        return json.readTree(jdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) SELECT * FROM transfers WHERE tenant_id=? ORDER BY accepted_at DESC LIMIT 20", String.class, tenant));
    }
    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
    private static void stop(Process process) throws InterruptedException {
        process.destroy(); if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
    }
    private void seedOnlyDisposableDatabase() {
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            var technical = UUID.randomUUID();
            jdbc.update("INSERT INTO accounts(id,tenant_id,owner_subject,kind,currency,balance_cents) VALUES (?,?,'system','TECHNICAL','BRL',-50000000)", technical, tenant);
            for (int i = 0; i < 50; i++) {
                var source = UUID.nameUUIDFromBytes(("f6-source-" + i).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                var destination = UUID.nameUUIDFromBytes(("f6-destination-" + i).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                jdbc.update("INSERT INTO accounts(id,tenant_id,owner_subject,kind,currency,balance_cents) VALUES (?,?,'alice','CUSTOMER','BRL',1000000),(?,?,'bob','CUSTOMER','BRL',0)", source, tenant, destination, tenant);
                var journal = UUID.randomUUID();
                jdbc.update("INSERT INTO journals(id,tenant_id,purpose,currency,created_at) VALUES (?,?,'SYNTHETIC_FUNDING','BRL',now())", journal, tenant);
                jdbc.update("INSERT INTO journal_entries(id,tenant_id,journal_id,account_id,signed_cents) VALUES (?,?,?,?,1000000),(?,?,?,?,-1000000)", UUID.randomUUID(), tenant, journal, source, UUID.randomUUID(), tenant, journal, technical);
                pairs.add(Map.of("source", source.toString(), "destination", destination.toString()));
            }
        });
    }
}
