import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** TLC HTTP API. Chat access is gated by AccountApi approval checks. */
public final class TlcServer {
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("TLC_PORT", "8080"));
    private static final int MAX_BODY = 4096;
    private static final int MAX_MESSAGES = 500;
    private static final List<Map<String,String>> MESSAGES = new CopyOnWriteArrayList<>();
    private static final Pattern FIELD = Pattern.compile("\\\"(sender|text)\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
    private static AccountApi accountApi;
    private TlcServer() {}

    public static void main(String[] args) throws IOException {
        if (PORT < 1 || PORT > 65535) throw new IOException("TLC_PORT must be between 1 and 65535");
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        String jdbcUrl = System.getenv().getOrDefault("TLC_DATABASE_URL", "jdbc:sqlite:tlc.db");
        try {
            accountApi = new AccountApi(server, jdbcUrl);
        } catch (Exception ex) {
            server.stop(0);
            throw new IOException("TLC account services failed to initialize; refusing to start unprotected", ex);
        }
        server.createContext("/api/status", e -> {
            cors(e); if (preflight(e)) return;
            if (!method(e, "GET")) return;
            send(e, 200, "{\"status\":\"TLC Java connected\",\"version\":\"account-gated\"}", "application/json; charset=utf-8");
        });
        server.createContext("/api/messages", TlcServer::messages);
        server.createContext("/", e -> {
            cors(e); if (preflight(e)) return;
            if (!method(e, "GET")) return;
            send(e, 200, "TLC server is running. Use /api/status and /api/messages.", "text/plain; charset=utf-8");
        });
        server.setExecutor(null);
        server.start();
        System.out.println("TLC listening on port " + PORT + " (approved accounts required for chat)");
    }

    private static void cors(HttpExchange e) {
        String allowed = System.getenv("TLC_ALLOWED_ORIGIN");
        String origin = e.getRequestHeaders().getFirst("Origin");
        if (allowed != null && !allowed.isBlank() && allowed.equals(origin)) {
            e.getResponseHeaders().set("Access-Control-Allow-Origin", allowed);
            e.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");
        }
        e.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        e.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-TLC-Admin-Secret");
        e.getResponseHeaders().set("Vary", "Origin");
    }

    private static boolean preflight(HttpExchange e) throws IOException {
        if (!"OPTIONS".equalsIgnoreCase(e.getRequestMethod())) return false;
        e.getResponseHeaders().set("Allow", "GET, POST, OPTIONS");
        e.sendResponseHeaders(204, -1); e.close(); return true;
    }

    private static boolean method(HttpExchange e, String expected) throws IOException {
        if (expected.equalsIgnoreCase(e.getRequestMethod())) return true;
        e.getResponseHeaders().set("Allow", expected + ", OPTIONS");
        send(e, 405, "{\"error\":\"Method not allowed\"}", "application/json; charset=utf-8");
        return false;
    }

    private static void messages(HttpExchange e) throws IOException {
        cors(e); if (preflight(e)) return;
        if (accountApi == null || !accountApi.requireApproved(e)) return;
        if ("GET".equalsIgnoreCase(e.getRequestMethod())) {
            StringBuilder out = new StringBuilder("{\"messages\":[");
            for (int i = 0; i < MESSAGES.size(); i++) {
                if (i > 0) out.append(',');
                Map<String,String> m = MESSAGES.get(i);
                out.append("{\"sender\":\"").append(escape(m.get("sender")))
                   .append("\",\"text\":\"").append(escape(m.get("text")))
                   .append("\",\"time\":\"").append(escape(m.get("time"))).append("\"}");
            }
            out.append("]}");
            send(e, 200, out.toString(), "application/json; charset=utf-8");
            return;
        }
        if (!"POST".equalsIgnoreCase(e.getRequestMethod())) { method(e, "GET or POST"); return; }
        byte[] body = e.getRequestBody().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) { send(e, 413, "{\"error\":\"Message too large\"}", "application/json; charset=utf-8"); return; }
        String json = new String(body, StandardCharsets.UTF_8);
        Matcher matcher = FIELD.matcher(json);
        String sender = null, text = null;
        while (matcher.find()) {
            String value = unescape(matcher.group(2));
            if ("sender".equals(matcher.group(1))) sender = value;
            else text = value;
        }
        if (sender == null || text == null || sender.trim().isEmpty() || text.trim().isEmpty()
                || sender.length() > 40 || text.length() > 1000) {
            send(e, 400, "{\"error\":\"Provide sender (1-40 chars) and text (1-1000 chars)\"}", "application/json; charset=utf-8");
            return;
        }
        // Sender remains client-provided in this prototype; the authenticated account
        // gate prevents anonymous access, but identity binding is a separate follow-up.
        Map<String,String> m = new LinkedHashMap<>();
        m.put("sender", sender.trim()); m.put("text", text.trim()); m.put("time", Instant.now().toString());
        MESSAGES.add(m);
        while (MESSAGES.size() > MAX_MESSAGES) MESSAGES.remove(0);
        send(e, 201, "{\"ok\":true}", "application/json; charset=utf-8");
    }

    private static String escape(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"); }
    private static String unescape(String s) { return s.replace("\\\"", "\"").replace("\\n", "\n").replace("\\r", "\r").replace("\\\\", "\\"); }
    private static void send(HttpExchange e, int code, String body, String type) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", type);
        e.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        e.getResponseHeaders().set("Cache-Control", "no-store");
        e.getResponseHeaders().set("X-Frame-Options", "DENY");
        e.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        e.sendResponseHeaders(code, b.length);
        try (OutputStream o = e.getResponseBody()) { o.write(b); }
    }
}
