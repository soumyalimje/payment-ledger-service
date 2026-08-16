import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Moves money between two accounts as one atomic double-entry transaction.
 *
 * Concurrency safety comes from "SELECT ... FOR UPDATE": this locks the
 * account rows so that if two transfers touch the same account at the same
 * time, the second one BLOCKS until the first commits or rolls back. That's
 * what prevents the classic race: "both requests read balance=100, both
 * decide it's enough, both succeed, balance goes negative."
 *
 * Deadlock prevention: when a transfer touches two accounts, we always lock
 * them in a fixed order (alphabetical by account_id), never "from then to".
 * If every transaction locks rows in the same order, circular waits can't
 * happen.
 */
public class LedgerService {

    public static class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException(String msg) { super(msg); }
    }

    /**
     * Debits `fromAccount` and credits `toAccount` by `amount` (in paise/cents),
     * as one transaction_id in ledger_entries. Caller controls commit/rollback
     * via the passed-in Connection (must have autoCommit=false).
     */
    // Upper bound on a single transfer. Without this, a huge `amount` could
    // overflow a long when added to a balance (Java silently wraps to a
    // negative number on overflow -- no exception, just a corrupted balance).
    // This cap is set far below Long.MAX_VALUE / 2, so even if it were
    // added to an already-large balance, overflow still can't happen.
    public static final long MAX_TRANSFER_AMOUNT = 1_000_000_000_00L; // 1,000,000,000.00 in the smallest currency unit

    public void transfer(Connection conn, String transactionId, String fromAccount, String toAccount, long amount) throws SQLException {
        if (amount <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive");
        }
        if (amount > MAX_TRANSFER_AMOUNT) {
            throw new IllegalArgumentException("Transfer amount exceeds maximum allowed (" + MAX_TRANSFER_AMOUNT + ")");
        }
        if (fromAccount.equals(toAccount)) {
            throw new IllegalArgumentException("from_account and to_account must be different");
        }

        // Lock both account rows in a fixed order to avoid deadlocks between
        // concurrent transfers that touch the same pair of accounts in reverse order.
        String first = fromAccount.compareTo(toAccount) < 0 ? fromAccount : toAccount;
        String second = fromAccount.compareTo(toAccount) < 0 ? toAccount : fromAccount;

        long firstBalance = lockAndGetBalance(conn, first);
        long secondBalance = lockAndGetBalance(conn, second);

        long fromBalance = fromAccount.equals(first) ? firstBalance : secondBalance;
        if (fromBalance < amount) {
            throw new InsufficientFundsException(
                "Account " + fromAccount + " has balance " + fromBalance + ", needs " + amount);
        }

        // Double-entry: debit is negative, credit is positive. They must sum to zero
        // (enforced again by the DB trigger as a second, independent safety net).
        insertLedgerEntry(conn, transactionId, fromAccount, -amount);
        insertLedgerEntry(conn, transactionId, toAccount, amount);

        adjustBalance(conn, fromAccount, -amount);
        adjustBalance(conn, toAccount, amount);
    }

    private long lockAndGetBalance(Connection conn, String accountId) throws SQLException {
        String sql = "SELECT balance FROM accounts WHERE account_id = ? FOR UPDATE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("No such account: " + accountId);
                }
                return rs.getLong("balance");
            }
        }
    }

    private void insertLedgerEntry(Connection conn, String transactionId, String accountId, long signedAmount) throws SQLException {
        String sql = "INSERT INTO ledger_entries (transaction_id, account_id, amount) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, transactionId);
            ps.setString(2, accountId);
            ps.setLong(3, signedAmount);
            ps.executeUpdate();
        }
    }

    private void adjustBalance(Connection conn, String accountId, long delta) throws SQLException {
        String sql = "UPDATE accounts SET balance = balance + ? WHERE account_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, delta);
            ps.setString(2, accountId);
            ps.executeUpdate();
        }
    }

    public long getBalance(Connection conn, String accountId) throws SQLException {
        String sql = "SELECT balance FROM accounts WHERE account_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("No such account: " + accountId);
                return rs.getLong("balance");
            }
        }
    }
}
