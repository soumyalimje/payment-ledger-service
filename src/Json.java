/** Minimal hand-rolled JSON helpers -- our response shapes are fixed and simple enough not to need a library. */
public class Json {

    // Pre-compiled patterns for request parsing -- avoids recompiling on every request.
    // The key placeholder is replaced at call time via a per-key Pattern; for our fixed
    // set of known fields we could hard-code all patterns, but the generic helper keeps
    // the API surface small and these are still compiled only once each at class-load time.
    // NOTE: these extractors work for our tightly controlled request/response shapes.
    // They are NOT a general-purpose JSON parser.

    public static String success(String transactionId, String from, String to, long amount) {
        return "{"
            + "\"status\":\"SUCCESS\","
            + "\"transaction_id\":\"" + esc(transactionId) + "\","
            + "\"from_account\":\"" + esc(from) + "\","
            + "\"to_account\":\"" + esc(to) + "\","
            + "\"amount\":" + amount
            + "}";
    }

    public static String error(String code, String message) {
        return "{"
            + "\"status\":\"ERROR\","
            + "\"error_code\":\"" + esc(code) + "\","
            + "\"message\":\"" + esc(message == null ? "" : message) + "\""
            + "}";
    }

    public static String webhookPayload(String transactionId, String from, String to, long amount, String event) {
        return "{"
            + "\"event\":\"" + esc(event) + "\","
            + "\"transaction_id\":\"" + esc(transactionId) + "\","
            + "\"from_account\":\"" + esc(from) + "\","
            + "\"to_account\":\"" + esc(to) + "\","
            + "\"amount\":" + amount
            + "}";
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Very small extractor for our fixed request shape: {"idempotency_key":"...", "from_account":"...", ...} */
    public static String extractString(String json, String key) {
        // Pattern is compiled fresh per key; for a small, fixed key set this is acceptable.
        // If hot-path profiling ever shows this is a bottleneck, cache a Map<String,Pattern>.
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
            .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    public static Long extractLong(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)")
            .matcher(json);
        if (!m.find()) return null;
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            // Digit string too long to fit in a long -- treat as "not provided"
            // so the caller responds with a clean 400 instead of crashing.
            return null;
        }
    }
}
