import java.sql.Connection;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Creates the database schema on first boot if it does not exist yet.
 *
 * Why this exists: on Render (and any managed Postgres) the service starts
 * against an EMPTY database -- there is no one to run `psql -f sql/schema.sql`.
 * Docker has the docker-entrypoint-initdb.d trick, but that only runs when a
 * fresh volume is initialized; a platform like Render hands the app an empty
 * database URL.
 *
 * Safe to run on every boot:
 *   - CREATE TABLE IF NOT EXISTS / CREATE INDEX IF NOT EXISTS: additive
 *   - the trigger function uses CREATE OR REPLACE, so it is additive
 *   - the constraint trigger is created only if pg_trigger says it's missing
 *   - seed accounts are inserted only when the accounts table did not exist
 *     before this boot, so restarts never reset balances
 */
public class SchemaBootstrap {

    private static final String CREATE_TRIGGER_SQL = """
        CREATE CONSTRAINT TRIGGER trg_check_ledger_balance
            AFTER INSERT ON ledger_entries
            DEFERRABLE INITIALLY DEFERRED
            FOR EACH ROW
            EXECUTE FUNCTION check_ledger_balance()
        """;

    private static final String[] STATEMENTS = {
        """
        CREATE TABLE IF NOT EXISTS accounts (
            account_id      TEXT PRIMARY KEY,
            balance         BIGINT NOT NULL DEFAULT 0
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS idempotency_keys (
            idempotency_key TEXT PRIMARY KEY,
            request_hash    TEXT NOT NULL,
            status          TEXT NOT NULL CHECK (status IN ('STARTED', 'COMPLETED', 'FAILED')),
            response_body   TEXT,
            created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS ledger_entries (
            entry_id        BIGSERIAL PRIMARY KEY,
            transaction_id  TEXT NOT NULL,
            account_id      TEXT NOT NULL REFERENCES accounts(account_id),
            amount          BIGINT NOT NULL,
            created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_ledger_transaction ON ledger_entries(transaction_id)",
        "CREATE INDEX IF NOT EXISTS idx_ledger_account ON ledger_entries(account_id)",
        """
        CREATE TABLE IF NOT EXISTS webhook_deliveries (
            delivery_id     BIGSERIAL PRIMARY KEY,
            url             TEXT NOT NULL,
            payload         TEXT NOT NULL,
            status          TEXT NOT NULL DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING', 'DELIVERED', 'ABANDONED')),
            attempt         INT NOT NULL DEFAULT 0,
            next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """,
        """
        CREATE INDEX IF NOT EXISTS idx_webhook_pending
            ON webhook_deliveries(next_attempt_at)
            WHERE status = 'PENDING'
        """,
        """
        CREATE OR REPLACE FUNCTION check_ledger_balance() RETURNS TRIGGER AS $$
        DECLARE
            tx_id TEXT;
            total BIGINT;
        BEGIN
            tx_id := NEW.transaction_id;
            SELECT COALESCE(SUM(amount), 0) INTO total
            FROM ledger_entries
            WHERE transaction_id = tx_id;
            IF total <> 0 THEN
                RAISE EXCEPTION 'Ledger imbalance for transaction %: entries sum to % (must be 0)', tx_id, total;
            END IF;
            RETURN NEW;
        END;
        $$ LANGUAGE plpgsql
        """,
    };

    public static void run(Connection conn) throws SQLException {
        boolean accountsTableExists = tableExists(conn, "accounts");

        try (Statement st = conn.createStatement()) {
            for (String sql : STATEMENTS) {
                st.execute(sql);
            }
        }

        if (!triggerExists(conn)) {
            try (Statement st = conn.createStatement()) {
                st.execute(CREATE_TRIGGER_SQL);
            }
        }

        // Seed only on the very first boot (accounts table was just created).
        if (!accountsTableExists) {
            try (Statement seed = conn.createStatement()) {
                seed.execute("INSERT INTO accounts (account_id, balance) VALUES ('merchant_A', 0)");
                seed.execute("INSERT INTO accounts (account_id, balance) VALUES ('customer_1', 1000000)");
            }
            System.out.println("[schema] created tables and seeded default accounts");
        } else {
            System.out.println("[schema] schema already present; left existing data untouched");
        }
    }

    private static boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = '" + tableName + "')")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private static boolean triggerExists(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT 1 FROM pg_trigger WHERE tgname = 'trg_check_ledger_balance'")) {
            return rs.next();
        }
    }
}
