import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Account API routes and authenticated session access for TLC. */
public final class AccountApi {
    private static final Pattern JSON_STRING = Pattern.compile("\\\"([A-Za-z][A-Za-z0-9_]*)\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long SESSION_TTL_MS = 12L * 60 * 60 * 1000;
    private final AccountStore accounts;
    private final AccountAccess access;
    private final String adminSecret;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private static final class Session {
        final String username;
        final long expires;
        Session(String username, long expires) { this.username = username; this.expires = expires; }
    }

    public AccountApi(HttpServer server, String jdbcUrl) throws Exception {
        accounts = new AccountStore(jdbcUrl);
        Connection connection = DriverManager.getConnection(jdbcUrl);
        access = new AccountAccess(connection);
        adminSecret = System.getenv("TLC_ADMIN_SECRET");
        server.createContext("/api/register", this::register);
        server.createContext("/api/login", this::login);
        server.createContext("/api/logout", this::logout);
        server.createContext("/api/account/me", this::me);
        server.createContext("/api/admin/pending", this::pending);
        server.createContext("/api/admin/status", this::setStatus);
    }

    private void register(HttpExchange e) throws IOException {
        if (!method(e, "POST")) return;
        Map<String, String> fields = fields(readBody(e));
        String username = fields.get("username"), password = fields.get("password");
        if (username == null || password == null) { send(e, 400, "{\"error\":\"username and password required\"}"); return; }
        char[] secret = password.toCharArray();
        try {
            if (!accounts.register(username, secret)) { send(e, 409, "{\"error\":\"Username unavailable\"}"); return; }
            access.addPending(username);
            send(e, 201, "{\"status\":\"PENDING\"}");
        } catch (IllegalArgumentException ex) { send(e, 400, "{\"error\":\"Invalid registration details\"}"); }
        catch (Exception ex) { send(e, 500, "{\"error\":\"Account creation failed\"}"); }
        finally { java.util.Arrays.fill(secret, '\0'); }
    }

    private void login(HttpExchange e) throws IOException {
        if (!method(e, "POST")) return;
        Map<String, String> fields = fields(readBody(e));
        String username = fields.get("username"), password = fields.get("password");
        if (username == null || password == null) { send(e, 400, "{\"error\":\"Credentials required\"}"); return; }
        char[] passwordChars = password.toCharArray();
        try {
            if (!accounts.verify(username, passwordChars)) { send(e, 401, "{\"error\":\"Invalid credentials\"}"); return; }
            if (access.getStatus(username) != AccountAccess.Status.APPROVED) { send(e, 403, "{\"status\":\"AWAITING_SYSADMIN_APPROVAL\"}"); return; }
            byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            sessions.put(token, new Session(username, System.currentTimeMillis() + SESSION_TTL_MS));
            e.getResponseHeaders().add("Set-Cookie", "TLC_SESSION=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200");
            send(e, 200, "{\"ok\":true,\"username\":\"" + escape(username) + "\"}");
        } catch (Exception ex) { send(e, 500, "{\"error\":\"Login unavailable\"}"); }
        finally { java.util.Arrays.fill(passwordChars, '\0'); }
    }

    private void logout(HttpExchange e) throws IOException {
        if (!method(e, "POST")) return;
        String token = cookie(e, "TLC_SESSION"); if (token != null) sessions.remove(token);
        e.getResponseHeaders().add("Set-Cookie", "TLC_SESSION=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
        send(e, 200, "{\"ok\":true}");
    }

    private void me(HttpExchange e) throws IOException {
        if (!method(e, "GET")) return;
        String username = authenticated(e);
        if (username == null) { send(e, 401, "{\"error\":\"Login required\"}"); return; }
        try { access.requireApproved(username); send(e, 200, "{\"username\":\"" + escape(username) + "\",\"status\":\"APPROVED\"}"); }
        catch (Exception ex) { sessions.remove(cookie(e, "TLC_SESSION")); send(e, 403, "{\"error\":\"Access revoked\"}"); }
    }

    private void pending(HttpExchange e) throws IOException {
        if (!method(e, "GET")) return;
        if (!owner(e)) { send(e, 403, "{\"error\":\"Owner access required\"}"); return; }
        try {
            StringBuilder body = new StringBuilder("{\"pending\":["); int count = 0;
            for (String username : access.listPending()) { if (count++ > 0) body.append(','); body.append('"').append(escape(username)).append('"'); }
            send(e, 200, body.append("]}").toString());
        } catch (SQLException ex) { send(e, 500, "{\"error\":\"Queue unavailable\"}"); }
    }

    private void setStatus(HttpExchange e) throws IOException {
        if (!method(e, "POST")) return;
        if (!owner(e)) { send(e, 403, "{\"error\":\"Owner access required\"}"); return; }
        Map<String, String> fields = fields(readBody(e));
        try {
            String username = fields.get("username"), value = fields.get("status");
            if (username == null || value == null) throw new IllegalArgumentException();
            AccountAccess.Status status = AccountAccess.Status.valueOf(value);
            access.setStatusByOwner(username, status);
            if (status != AccountAccess.Status.APPROVED) sessions.entrySet().removeIf(entry -> entry.getValue().username.equalsIgnoreCase(username));
            send(e, 200, "{\"ok\":true,\"status\":\"" + status.name() + "\"}");
        } catch (IllegalArgumentException ex) { send(e, 400, "{\"error\":\"Invalid account or status\"}"); }
        catch (SQLException ex) { send(e, 500, "{\"error\":\"Status update failed\"}"); }
    }

    /** Returns the approved username for this session, or sends an auth error and returns null. */
    public String approvedUsername(HttpExchange e) throws IOException {
        String username = authenticated(e);
        if (username == null) { send(e, 401, "{\"error\":\"Login required\"}"); return null; }
        try { access.requireApproved(username); return username; }
        catch (Exception ex) {
            String token = cookie(e, "TLC_SESSION");
            if (token != null) sessions.remove(token);
            send(e, 403, "{\"error\":\"Account not approved\"}");
            return null;
        }
    }

    public boolean requireApproved(HttpExchange e) throws IOException { return approvedUsername(e) != null; }

    private boolean owner(HttpExchange e) {
        if (adminSecret == null || adminSecret.length() < 24) return false;
        String supplied = e.getRequestHeaders().getFirst("X-TLC-Admin-Secret");
        return supplied != null && MessageDigest.isEqual(adminSecret.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private String authenticated(HttpExchange e) {
        String token = cookie(e, "TLC_SESSION"); if (token == null) return null;
        Session session = sessions.get(token); if (session == null) return null;
        if (session.expires < System.currentTimeMillis()) { sessions.remove(token); return null; }
        return session.username;
    }

    private static String cookie(HttpExchange e, String name) {
        String header = e.getRequestHeaders().getFirst("Cookie"); if (header == null) return null;
        for (String part : header.split(";")) { String[] pair = part.trim().split("=", 2); if (pair.length == 2 && pair[0].equals(name)) return pair[1]; }
        return null;
    }
    private static boolean method(HttpExchange e, String expected) throws IOException {
        if (expected.equalsIgnoreCase(e.getRequestMethod())) return true;
        send(e, 405, "{\"error\":\"Method not allowed\"}"); return false;
    }
    private static String readBody(HttpExchange e) throws IOException {
        byte[] bytes = e.getRequestBody().readNBytes(4097); if (bytes.length > 4096) throw new IOException("Request too large");
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static Map<String, String> fields(String json) {
        Map<String, String> result = new java.util.HashMap<>(); Matcher matcher = JSON_STRING.matcher(json);
        while (matcher.find()) result.put(matcher.group(1), unescape(matcher.group(2))); return result;
    }
    private static String unescape(String value) { return value.replace("\\\"", "\"").replace("\\n", "\n").replace("\\r", "\r").replace("\\\\", "\\"); }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"); }
    private static void send(HttpExchange e, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        e.getResponseHeaders().set("Cache-Control", "no-store"); e.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        e.sendResponseHeaders(code, bytes.length);
        try (java.io.OutputStream output = e.getResponseBody()) { output.write(bytes); }
    }
}
