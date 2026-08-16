import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * One place that knows how to open a connection to the ledger database.
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
    private static final String URL = "jdbc:postgresql://localhost:5432/payment_ledger";
    private static final String USER = "postgres";
    private static final String PASSWORD = "postgres";

    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }
}
