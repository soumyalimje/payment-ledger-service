import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Exposes: POST /payments
 * Body: {"idempotency_key":"...", "from_account":"...", "to_account":"...", "amount":500, "webhook_url":"..."}
 */
public class PaymentServer {

    public static void main(String[] args) throws IOException {
        WebhookService webhookService = new WebhookService();
        PaymentService paymentService = new PaymentService(webhookService);

        int port = 8090;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/payments", exchange -> handlePayment(exchange, paymentService));
        server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"ok\"}"));

        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(20));
        server.start();
        System.out.println("Payment service listening on port " + port);
    }

    private static void handlePayment(HttpExchange exchange, PaymentService paymentService) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, Json.error("method_not_allowed", "use POST"));
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            String idempotencyKey = Json.extractString(body, "idempotency_key");
            String fromAccount = Json.extractString(body, "from_account");
            String toAccount = Json.extractString(body, "to_account");
            Long amount = Json.extractLong(body, "amount");
            String webhookUrl = Json.extractString(body, "webhook_url"); // optional

            if (idempotencyKey == null || idempotencyKey.isBlank()
                    || fromAccount == null || toAccount == null || amount == null) {
                respond(exchange, 400, Json.error("bad_request",
                    "requires non-empty idempotency_key, from_account, to_account, amount (positive integer)"));
                return;
            }

            if (webhookUrl != null && !webhookUrl.isBlank() && !WebhookService.isUrlSafe(webhookUrl)) {
                respond(exchange, 400, Json.error("unsafe_webhook_url",
                    "webhook_url must be a public http(s) address, not loopback/private/link-local"));
                return;
            }

            PaymentService.PaymentResult result = paymentService.processPayment(
                idempotencyKey, fromAccount, toAccount, amount, webhookUrl);

            respond(exchange, result.httpStatus, result.jsonBody);

        } catch (Exception e) {
            // Absolute last line of defense: PaymentService should already handle
            // everything it can, but if something truly unexpected slips through,
            // the client still gets a real HTTP response instead of a hung
            // connection or an empty reply.
            System.out.println("[error] unhandled exception in /payments: " + e);
            respond(exchange, 500, Json.error("internal_error", "unexpected server error"));
        }
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
