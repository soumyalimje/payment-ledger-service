import java.sql.Connection;
import java.sql.ResultSet;

/**
 * Restart-safety test driver. Deliberately exercises WebhookService at the
 * SERVICE layer, not through the HTTP API: the API layer's SSRF guard
 * (correctly) refuses loopback webhook URLs, and a self-contained test
 * needs a local failing endpoint. SSRF enforcement itself is covered
 * end-to-end by the functional suite (TEST 12); this driver isolates the
 * v2 feature under test: durable retry state that survives process death.
 *
 * Phase 1 ("phase1"): enqueue a doomed webhook, wait until attempt 1 has
 *   failed and the retry is durably scheduled, then HARD-EXIT the JVM --
 *   simulating a crash mid-retry with a PENDING row left behind.
 *
 * Phase 2 ("phase2"): a fresh JVM constructs WebhookService, whose
 *   constructor must recover the PENDING row and resume the schedule
 *   through to ABANDONED. Stays alive while that plays out, then exits.
 *
 * Run via the single-file source launcher (no compile step):
 *   java -cp "out:postgresql.jar" tests/RestartRecoveryDriver.java phase1 <port> <payload-id>
 */
public class RestartRecoveryDriver {

    public static void main(String[] args) throws Exception {
        String phase = args[0];
        int failPort = Integer.parseInt(args[1]);
        String key = args[2];

        String url = "http://localhost:" + failPort + "/doomed-" + key;
        String payload = "{\"event\":\"PAYMENT_COMPLETED\",\"transaction_id\":\"" + key + "\"}";

        switch (phase) {
            case "phase1" -> phase1(url, payload, key);
            case "phase2" -> phase2();
            default -> throw new IllegalArgumentException("unknown phase: " + phase);
        }
    }

    private static void phase1(String url, String payload, String key) throws Exception {
        // Fresh service -- recovery finds nothing pending on a clean schema.
        WebhookService service = new WebhookService();
        service.enqueue(url, payload);

        // Wait until attempt 1 has failed fast and the reschedule (attempt 2,
        // +2s backoff) is durable in webhook_deliveries.
        waitForRowState("PENDING", 2, 10_000);
        System.out.println("PHASE1_OK: delivery row durably PENDING at attempt 2; crashing JVM now");
        System.out.flush();
        Runtime.getRuntime().halt(0); // hard stop -- no clean shutdown, like a real crash
    }

    private static void phase2() throws Exception {
        // Constructor must reload every PENDING row from the database.
        WebhookService service = new WebhookService(); //NOSONAR -- daemon worker does the work

        // Remaining schedule after recovery: overdue attempt 2 fires at once,
        // fails -> +4s (attempt 3), fails -> +8s (attempt 4), fails ->
        // ABANDONED. Cumulative: 0 + 4 + 8 = 12s of waiting, so sleep 16s
        // to cover attempt 4's delivery AND its markAbandoned write.
        Thread.sleep(16_000);
        System.out.println("PHASE2_DONE");
        System.out.flush();
        System.exit(0);
    }

    /** Polls the DB until the single delivery row reaches status+attempt. */
    private static void waitForRowState(String status, int attempt, long timeoutMs) throws Exception {
        String sql = "SELECT COUNT(*) FROM webhook_deliveries WHERE status=? AND attempt=?";
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (Connection conn = Db.connect();
                 java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, status);
                ps.setInt(2, attempt);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) >= 1) return;
                }
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("delivery row never reached " + status + ":" + attempt
                + " within " + timeoutMs + "ms");
    }
}
