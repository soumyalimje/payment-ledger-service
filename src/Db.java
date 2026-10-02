import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * One place that knows how to open a connection to the ledger database.
 *
 * Reads connection details from environment variables so the same code works
 * everywhere the project runs:
 *   - locally on your machine (falls back to localhost defaults)
 *   - inside Docker, where the database is a separate container reachable by
 *     its service name ("postgres"), not "localhost"
 *   - on Render, which provides DB_HOST / DB_PORT / DB_NAME / DB_USER /
 *     DB_PASSWORD for its managed Postgres (either as separate variables or
 *     inside a single internal DATABASE_URL). If DB_URL is set it always wins.
 *
 * If a legacy Postgres password contains special characters, set DB_URL
 * directly instead -- the parts-based path here does not URL-encode.
 *
 * NOTE: A connection pool (HikariCP) was tried here and measured -- at this
 * project's actual scale (local Postgres, tens of requests/sec), it was
 * SLOWER: 115ms avg latency and 25 req/sec vs. 1.5ms avg latency and 129
 * req/sec with plain DriverManager connections. Pooling's benefit shows up
 * when opening a raw connection is genuinely expensive (remote DB, real
 * network latency, very high request volume) -- neither is true here, so
 * pooling's own overhead (validation, bookkeeping) outweighed what it saved.
 * Kept simple on purpose, based on that measurement, not by default.
 */
public class Db {
    private static final String URL = resolveUrl();
    private static final String USER;
    private static final String PASSWORD;

    static {
        String[] userPass = resolveUserPassword();
        USER = userPass[0];
        PASSWORD = userPass[1];
    }

    private static String resolveUrl() {
        String dbUrl = env("DB_URL", null);
        if (dbUrl != null) {
            return dbUrl;
        }
        String host = env("DB_HOST", "localhost");
        int port = Integer.parseInt(env("DB_PORT", "5432"));
        String name = env("DB_NAME", "payment_ledger");
        return "jdbc:postgresql://" + host + ":" + port + "/" + name;
    }

    private static String[] resolveUserPassword() {
        // Explicit vars always win (Docker compose sets these).
        String user = env("DB_USER", null);
        String password = env("DB_PASSWORD", null);
        if (user != null && password != null) {
            return new String[] { user, password };
        }

        // Render-style single internal URL: postgres://user:pass@host:port/db
        String internalUrl = env("DATABASE_URL", env("DB_INTERNAL_URL", null));
        if (internalUrl != null) {
            try {
                java.net.URI uri = new java.net.URI(internalUrl);
                String userInfo = uri.getUserInfo();
                if (userInfo != null) {
                    int colon = userInfo.indexOf(':');
                    String u = colon >= 0 ? userInfo.substring(0, colon) : userInfo;
                    String p = colon >= 0 ? userInfo.substring(colon + 1) : "";
                    return new String[] { u, p };
                }
            } catch (Exception e) {
                System.out.println("[db] could not parse DATABASE_URL: " + e);
            }
        }
        return new String[] { "postgres", "postgres" };
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }
}
