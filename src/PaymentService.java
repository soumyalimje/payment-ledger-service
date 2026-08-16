import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Ties the three pieces together for one incoming payment request:
 *   1. Idempotency check (is this a duplicate? in-flight? new?)
 *   2. Ledger transfer (the actual money movement, with locking)
 *   3. Webhook notification (fire-and-forget, async, with retry)
 */
public class PaymentService {

    public static class PaymentResult {
        public final int httpStatus;
        public final String jsonBody;
        PaymentResult(int httpStatus, String jsonBody) {
            this.httpStatus = httpStatus;
            this.jsonBody = jsonBody;
        }
    }

    private final IdempotencyService idempotencyService = new IdempotencyService();
    private final LedgerService ledgerService = new LedgerService();
    private final WebhookService webhookService;

    public PaymentService(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    public PaymentResult processPayment(String idempotencyKey, String fromAccount, String toAccount, long amount, String webhookUrl) {
        String requestHash = Integer.toHexString((fromAccount + ":" + toAccount + ":" + amount).hashCode());

        // --- Step 1: claim the idempotency key in its own short transaction,
        // so the STARTED row is committed and visible to concurrent requests immediately. ---
        IdempotencyService.CheckResult check;
        try (Connection conn = Db.connect()) {
            conn.setAutoCommit(true);
            check = idempotencyService.checkAndStart(conn, idempotencyKey, requestHash);
        } catch (SQLException e) {
            return new PaymentResult(500, Json.error("db_error_idempotency_check", e.getMessage()));
        }

        switch (check.outcome) {
            case IN_FLIGHT:
                return new PaymentResult(409, Json.error("in_flight", "A request with this idempotency key is already being processed"));
            case ALREADY_COMPLETED:
                return new PaymentResult(200, check.cachedResponse);
            case ALREADY_FAILED:
                return new PaymentResult(422, check.cachedResponse);
            case HASH_MISMATCH:
                return new PaymentResult(409, Json.error("idempotency_key_reused",
                    "This idempotency key was already used with different request parameters"));
            case PROCEED:
                break; // fall through to actually process it
        }

        // --- Step 2: do the actual transfer in its own transaction ---
        String transactionId = UUID.randomUUID().toString();
        String responseBody;
        int status;

        try (Connection conn = Db.connect()) {
            conn.setAutoCommit(false);
            try {
                ledgerService.transfer(conn, transactionId, fromAccount, toAccount, amount);
                conn.commit();
                responseBody = Json.success(transactionId, fromAccount, toAccount, amount);
                status = 200;
            } catch (LedgerService.InsufficientFundsException e) {
                conn.rollback();
                responseBody = Json.error("insufficient_funds", e.getMessage());
                status = 422;
            } catch (IllegalArgumentException e) {
                // Bad account_id, non-positive amount, self-transfer, etc.
                // These are client errors, not server errors -- and critically,
                // we still fall through to Step 3 below so the idempotency key
                // gets resolved instead of being stuck in STARTED forever.
                conn.rollback();
                responseBody = Json.error("invalid_request", e.getMessage());
                status = 400;
            } catch (SQLException e) {
                conn.rollback();
                responseBody = Json.error("ledger_db_error", e.getMessage());
                status = 500;
            } catch (RuntimeException e) {
                // Last-resort safety net: whatever this is, we still must not
                // skip Step 3, or this idempotency key would be stuck in
                // STARTED until the 30s stale-reclaim window kicks in.
                conn.rollback();
                responseBody = Json.error("unexpected_error", e.getMessage());
                status = 500;
            }
        } catch (SQLException e) {
            responseBody = Json.error("db_connection_error", e.getMessage());
            status = 500;
        }

        // --- Step 3: record the final outcome against the idempotency key ---
        try (Connection conn = Db.connect()) {
            conn.setAutoCommit(true);
            if (status == 200) {
                idempotencyService.markCompleted(conn, idempotencyKey, responseBody);
            } else {
                idempotencyService.markFailed(conn, idempotencyKey, responseBody);
            }
        } catch (SQLException e) {
            System.out.println("[warn] failed to update idempotency record: " + e.getMessage());
        }

        // --- Step 4: fire the webhook asynchronously (never blocks this response) ---
        if (status == 200 && webhookUrl != null && !webhookUrl.isBlank()) {
            String payload = Json.webhookPayload(transactionId, fromAccount, toAccount, amount, "PAYMENT_COMPLETED");
            webhookService.enqueue(webhookUrl, payload);
        }

        return new PaymentResult(status, responseBody);
    }
}
