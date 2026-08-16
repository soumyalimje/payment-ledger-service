import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
 * Implementation note: a DelayQueue is a queue that only lets you take()
 * an item once its scheduled delay has elapsed. That's exactly what we
 * need for "retry after N seconds" without busy-waiting or extra timers.
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
        this.workerThread = new Thread(this::runWorkerLoop, "webhook-worker");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
    }

    /** Enqueue a webhook to be delivered immediately (attempt 1, no delay). */
    public void enqueue(String url, String jsonPayload) {
        queue.put(new WebhookTask(url, jsonPayload, 1, System.currentTimeMillis()));
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
            try {
                WebhookTask task = queue.take(); // blocks until a task's delay has elapsed
                deliver(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
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
            return;
        }
        long delayMs = BASE_DELAY_MS * (1L << (task.attempt - 1)); // 2s, 4s, 8s, ...
        long nextAttemptAt = System.currentTimeMillis() + delayMs;
        System.out.println("[webhook] retrying in " + (delayMs / 1000) + "s (attempt " + (task.attempt + 1) + "/" + MAX_ATTEMPTS + ")");
        queue.put(new WebhookTask(task.url, task.payload, task.attempt + 1, nextAttemptAt));
    }

    public void shutdown() {
        running.set(false);
        workerThread.interrupt();
    }

    /** A single scheduled webhook delivery attempt. */
    private static class WebhookTask implements Delayed {
        final String url;
        final String payload;
        final int attempt;
        final long readyAtMillis;

        WebhookTask(String url, String payload, int attempt, long readyAtMillis) {
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
