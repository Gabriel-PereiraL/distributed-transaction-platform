import java.net.HttpURLConnection;
import java.net.URI;

/** Standalone readiness probe; no curl/package installation or secrets in its output. */
public final class ContainerHealth {
    private ContainerHealth() { }
    public static void main(String[] args) {
        try {
            int port = Integer.parseInt(System.getenv().getOrDefault("DTP_PORT", "8085"));
            if (port < 1 || port > 65535) System.exit(1);
            var connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/actuator/health/readiness").toURL().openConnection();
            connection.setConnectTimeout(1500); connection.setReadTimeout(2500);
            int code;
            try { code = connection.getResponseCode(); } finally { connection.disconnect(); }
            System.exit(code == 200 ? 0 : 1);
        } catch (Exception unavailable) { System.exit(1); }
    }
}
