import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Idempotency layer.
 *
 * The core trick: we INSERT the row with status=STARTED *before* doing any
 * business logic, using "ON CONFLICT DO NOTHING". Postgres guarantees that
 * if two requests race to insert the same idempotency_key, only ONE insert
 * succeeds -- the primary key constraint is what makes this atomic, not
 * application code. Whoever's insert is silently skipped is the duplicate.
 */
public class IdempotencyService {

    public enum Outcome { PROCEED, IN_FLIGHT, ALREADY_COMPLETED, ALREADY_FAILED, HASH_MISMATCH }

    // If a key has been stuck in STARTED longer than this, we assume the
    // process handling it crashed (or threw something unexpected) before
    // it could mark the key COMPLETED/FAILED, and we let a new request
    // reclaim and retry it -- otherwise a crash mid-request would leave
    // that key permanently stuck returning 409 forever.
    private static final int STALE_STARTED_SECONDS = 30;

    public static class CheckResult {
        public final Outcome outcome;
        public final String cachedResponse; // set for ALREADY_COMPLETED / ALREADY_FAILED
        CheckResult(Outcome outcome, String cachedResponse) {
            this.outcome = outcome;
            this.cachedResponse = cachedResponse;
        }
    }

    /**
     * Tries to "claim" this idempotency key. Must be called before any
     * business logic runs.
     */
    public CheckResult checkAndStart(Connection conn, String idempotencyKey, String requestHash) throws SQLException {
        String insertSql = """
            INSERT INTO idempotency_keys (idempotency_key, request_hash, status)
            VALUES (?, ?, 'STARTED')
            ON CONFLICT (idempotency_key) DO NOTHING
            """;
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setString(1, idempotencyKey);
            ps.setString(2, requestHash);
            int inserted = ps.executeUpdate();
            if (inserted == 1) {
                // We won the race -- we're the one who gets to process this request.
                return new CheckResult(Outcome.PROCEED, null);
            }
        }

        // Someone else already owns this key (or owned it before). Find out what state it's in.
        String selectSql = "SELECT status, response_body, request_hash FROM idempotency_keys WHERE idempotency_key = ?";
        String existingStatus;
        String existingHash;
        String existingResponse;
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setString(1, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // Extremely unlikely race: row vanished between insert-failed and select.
                    throw new IllegalStateException("Idempotency key vanished unexpectedly: " + idempotencyKey);
                }
                existingStatus = rs.getString("status");
                existingHash = rs.getString("request_hash");
                existingResponse = rs.getString("response_body");
            }
        }

        // Same key, different request body (different accounts/amount) -- this is a
        // client bug, not a legitimate retry. Never silently reuse the old result.
        if (!existingHash.equals(requestHash)) {
            return new CheckResult(Outcome.HASH_MISMATCH, null);
        }

        switch (existingStatus) {
            case "COMPLETED":
                return new CheckResult(Outcome.ALREADY_COMPLETED, existingResponse);
            case "FAILED":
                return new CheckResult(Outcome.ALREADY_FAILED, existingResponse);
            default: // STARTED
                if (tryReclaimStale(conn, idempotencyKey)) {
                    return new CheckResult(Outcome.PROCEED, null);
                }
                return new CheckResult(Outcome.IN_FLIGHT, null);
        }
    }

    /**
     * If a key has sat in STARTED for longer than STALE_STARTED_SECONDS, the
     * request that owned it almost certainly crashed or threw before
     * finishing. This atomically hands ownership to the current request --
     * the WHERE clause means only one concurrent reclaimer can win, same
     * atomicity guarantee as the original insert.
     */
    private boolean tryReclaimStale(Connection conn, String idempotencyKey) throws SQLException {
        String sql = """
            UPDATE idempotency_keys
            SET status = 'STARTED', updated_at = now()
            WHERE idempotency_key = ?
              AND status = 'STARTED'
              AND updated_at < now() - (? || ' seconds')::interval
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, idempotencyKey);
            ps.setInt(2, STALE_STARTED_SECONDS);
            return ps.executeUpdate() == 1;
        }
    }

    public void markCompleted(Connection conn, String idempotencyKey, String responseBody) throws SQLException {
        updateStatus(conn, idempotencyKey, "COMPLETED", responseBody);
    }

    public void markFailed(Connection conn, String idempotencyKey, String errorBody) throws SQLException {
        updateStatus(conn, idempotencyKey, "FAILED", errorBody);
    }

    private void updateStatus(Connection conn, String key, String status, String body) throws SQLException {
        String sql = "UPDATE idempotency_keys SET status = ?, response_body = ?, updated_at = ? WHERE idempotency_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, body);
            ps.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
            ps.setString(4, key);
            ps.executeUpdate();
        }
    }
}
