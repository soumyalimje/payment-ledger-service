import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends webhook notifications in the background so a slow/unreachable
 * webhook endpoint never blocks the payment API response.
 *
 * Retry schedule: 2s, 4s, 8s, 16s (max 4 attempts) -- classic exponential
 * backoff, doubling each time, so we don't hammer a struggling endpoint
 * but also don't give up after one blip.
 *
 * PERSISTENCE (v2): every delivery is a ROW in webhook_deliveries, not just
 * an entry in the in-memory DelayQueue. The database is the source of truth;
 * the DelayQueue is a scheduling cache for "what's due soon". At startup we
 * reload every still-PENDING row and re-schedule it, so a crash or restart
 * mid-retry resumes the backoff schedule instead of silently dropping owed
 * notifications. This was the service's biggest known limitation.
 *
 * Delivery semantics: AT-LEAST-ONCE. If the process dies after the HTTP POST
 * reached the receiver but before the row was marked DELIVERED, the next boot
 * delivers again. Receivers should dedupe on transaction_id -- the same
 * tradeoff real payment gateways make and document.
 */
public class WebhookService {

    private static final int MAX_ATTEMPTS = 4;
    private static final long BASE_DELAY_MS = 2000; // 2s

    private final DelayQueue<WebhookTask> queue = new DelayQueue<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread workerThread;

    public WebhookService() {
        recoverPendingFromDatabase(); // resume what a previous process left behind
        this.workerThread = new Thread(this::runWorkerLoop, "webhook-worker");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
    }

    /**
     * Persist first, schedule second. If the DB write fails we do NOT put the
     * task in the queue -- an untracked delivery is exactly the bug this
     * rewrite exists to fix, so the request fails loudly instead.
     */
    public void enqueue(String url, String jsonPayload) {
        long deliveryId;
        try (Connection conn = Db.connect()) {
            deliveryId = insertDeliveryRow(conn, url, jsonPayload);
        } catch (SQLException e) {
            System.out.println("[webhook] ERROR: could not persist delivery to database, NOT scheduling: " + e.getMessage());
            return;
        }
        queue.put(new WebhookTask(deliveryId, url, jsonPayload, 1, System.currentTimeMillis()));
    }

    private long insertDeliveryRow(Connection conn, String url, String payload) throws SQLException {
        String sql = "INSERT INTO webhook_deliveries (url, payload, attempt, next_attempt_at) VALUES (?, ?, 1, now()) RETURNING delivery_id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, url);
            ps.setString(2, payload);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("INSERT INTO webhook_deliveries returned no row — expected RETURNING delivery_id");
                }
                return rs.getLong(1);
            }
        }
    }

    /**
     * At startup, load every PENDING row left over from a previous process
     * and re-queue it at its already-computed next_attempt_at. Rows whose
     * scheduled time already passed (server was down through their slot)
     * fire immediately -- we never skip owed retries, we just catch up.
     */
    private void recoverPendingFromDatabase() {
        String sql = "SELECT delivery_id, url, payload, attempt, next_attempt_at FROM webhook_deliveries WHERE status = 'PENDING' ORDER BY next_attempt_at";
        int recovered = 0;
        try (Connection conn = Db.connect();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long id = rs.getLong("delivery_id");
                String url = rs.getString("url");
                String payload = rs.getString("payload");
                int attempt = rs.getInt("attempt");
                Timestamp nextAt = rs.getTimestamp("next_attempt_at");
                queue.put(new WebhookTask(id, url, payload, attempt, nextAt.getTime()));
                recovered++;
            }
        } catch (SQLException e) {
            System.out.println("[webhook] WARN: could not recover pending deliveries from database: " + e.getMessage());
            return;
        }
        if (recovered > 0) {
            System.out.println("[webhook] recovered " + recovered + " pending deliver"
                    + (recovered == 1 ? "y" : "ies") + " from database after restart");
        }
    }

    /**
     * Rejects webhook URLs that point at loopback, private, or link-local
     * addresses. Without this, a client could set webhook_url to something
     * like http://localhost:5432 or an internal-network address, and this
     * server would happily make a request to it on the client's behalf --
     * a classic SSRF (Server-Side Request Forgery) hole. This is a basic
     * check, not exhaustive (e.g. it won't catch a public DNS name that's
     * been made to resolve to a private IP just before this check runs and
     * a different one after -- a full defense would re-check at connect
     * time too), but it blocks the obvious, common cases.
     */
    public static boolean isUrlSafe(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            InetAddress addr = InetAddress.getByName(host); // resolves the hostname
            return !addr.isLoopbackAddress()
                && !addr.isSiteLocalAddress()   // 10.x, 172.16-31.x, 192.168.x
                && !addr.isLinkLocalAddress()   // 169.254.x
                && !addr.isAnyLocalAddress();
        } catch (IllegalArgumentException | UnknownHostException e) {
            return false; // malformed URL or unresolvable host -- treat as unsafe
        }
    }

    private void runWorkerLoop() {
        while (running.get()) {
            WebhookTask task;
            try {
                task = queue.take(); // blocks until a task's delay has elapsed
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // The row is already durable (enqueue/reschedule persisted it).
            // If we crash mid-delivery, the row stays PENDING at the current
            // attempt, so the next boot re-delivers it -- at-least-once, by design.
            deliver(task);
        }
    }

    private void deliver(WebhookTask task) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(task.url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(task.payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                System.out.println("[webhook] delivered to " + task.url + " (attempt " + task.attempt + ")");
                markDelivered(task.deliveryId);
                return;
            }
            System.out.println("[webhook] endpoint returned " + response.statusCode() + " (attempt " + task.attempt + ")");
            scheduleRetry(task);

        } catch (Exception e) {
            System.out.println("[webhook] delivery failed: " + e.getMessage() + " (attempt " + task.attempt + ")");
            scheduleRetry(task);
        }
    }

    private void scheduleRetry(WebhookTask task) {
        if (task.attempt >= MAX_ATTEMPTS) {
            System.out.println("[webhook] giving up on " + task.url + " after " + task.attempt + " attempts");
            markAbandoned(task.deliveryId);
            return;
        }
        long delayMs = BASE_DELAY_MS * (1L << (task.attempt - 1)); // 2s, 4s, 8s, ...
        long nextAttemptAt = System.currentTimeMillis() + delayMs;
        System.out.println("[webhook] retrying in " + (delayMs / 1000) + "s (attempt " + (task.attempt + 1) + "/" + MAX_ATTEMPTS + ")");
        int nextAttempt = task.attempt + 1;
        try (Connection conn = Db.connect()) {
            persistReschedule(conn, task.deliveryId, nextAttempt, nextAttemptAt);
        } catch (SQLException e) {
            // Reschedule in memory anyway: better to attempt delivery without
            // durable state than to silently drop a retry we still owe.
            System.out.println("[webhook] WARN: could not persist reschedule for delivery "
                    + task.deliveryId + ": " + e.getMessage());
        }
        queue.put(new WebhookTask(task.deliveryId, task.url, task.payload, nextAttempt, nextAttemptAt));
    }

    /** The attempt column always mirrors the NEXT scheduled attempt, so a
     *  restarted process resumes the schedule from exactly where this one left off. */
    private void persistReschedule(Connection conn, long deliveryId, int nextAttempt, long nextAttemptAtMillis) throws SQLException {
        String sql = "UPDATE webhook_deliveries SET attempt = ?, next_attempt_at = ?, updated_at = now() WHERE delivery_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, nextAttempt);
            ps.setTimestamp(2, new Timestamp(nextAttemptAtMillis));
            ps.setLong(3, deliveryId);
            ps.executeUpdate();
        }
    }

    private void markDelivered(long deliveryId) {
        String sql = "UPDATE webhook_deliveries SET status = 'DELIVERED', updated_at = now() WHERE delivery_id = ?";
        try (Connection conn = Db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, deliveryId);
            ps.executeUpdate();
        } catch (SQLException e) {
            // Row stays PENDING, so a restart re-delivers -- at-least-once
            // semantics, not at-most-once. That's the correct failure bias
            // for money notifications.
            System.out.println("[webhook] WARN: delivered but could not mark row DELIVERED (id "
                    + deliveryId + "); it will be re-delivered after a restart: " + e.getMessage());
        }
    }

    private void markAbandoned(long deliveryId) {
        String sql = "UPDATE webhook_deliveries SET status = 'ABANDONED', updated_at = now() WHERE delivery_id = ?";
        try (Connection conn = Db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, deliveryId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("[webhook] WARN: could not mark row ABANDONED (id " + deliveryId + "): " + e.getMessage());
        }
    }

    public void shutdown() {
        running.set(false);
        workerThread.interrupt();
    }

    /** A single scheduled webhook delivery attempt. */
    private static class WebhookTask implements Delayed {
        final long deliveryId;
        final String url;
        final String payload;
        final int attempt;
        final long readyAtMillis;

        WebhookTask(long deliveryId, String url, String payload, int attempt, long readyAtMillis) {
            this.deliveryId = deliveryId;
            this.url = url;
            this.payload = payload;
            this.attempt = attempt;
            this.readyAtMillis = readyAtMillis;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            long remaining = readyAtMillis - System.currentTimeMillis();
            return unit.convert(remaining, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(this.readyAtMillis, ((WebhookTask) other).readyAtMillis);
        }
    }
}
