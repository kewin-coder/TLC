import java.sql.*;
import java.security.*;
import java.util.*;

/** Local account approval/access-control primitives. Integrate into HTTP routes before deployment. */
public final class AccountAccess {
    public enum Status { PENDING, APPROVED, REJECTED }
    private final Connection db;

    public AccountAccess(Connection connection) throws SQLException {
        this.db = Objects.requireNonNull(connection);
        try (Statement s = db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS account_access (username TEXT PRIMARY KEY COLLATE NOCASE, status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPROVED','REJECTED')), updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    /** Every newly registered account must be inserted as PENDING. */
    public synchronized void addPending(String username) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("INSERT INTO account_access(username,status) VALUES(?, 'PENDING')")) {
            p.setString(1, username); p.executeUpdate();
        }
    }

    /** Call only after authenticating the owner; never expose this method as an unauthenticated route. */
    public synchronized void setStatusByOwner(String username, Status status) throws SQLException {
        Objects.requireNonNull(status);
        try (PreparedStatement p = db.prepareStatement("UPDATE account_access SET status=?, updated_at=CURRENT_TIMESTAMP WHERE username=?")) {
            p.setString(1, status.name()); p.setString(2, username);
            if (p.executeUpdate() != 1) throw new SQLException("Account not found");
        }
    }

    public synchronized Status getStatus(String username) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("SELECT status FROM account_access WHERE username=?")) {
            p.setString(1, username);
            try (ResultSet r = p.executeQuery()) { return r.next() ? Status.valueOf(r.getString(1)) : null; }
        }
    }

    /** Gate all network-facing routes with this check, not just the login screen. */
    public synchronized boolean mayAccessNetwork(String username) throws SQLException {
        return getStatus(username) == Status.APPROVED;
    }
}
