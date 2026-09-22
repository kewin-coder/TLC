import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTTP entry point for TLC. Serves the client and account-gated API from one origin. */
public final class TlcServer {
    private static final int PORT = readPort();
    private static final int MAX_BODY_BYTES = 4096;
    private static final int MAX_MESSAGE_CHARS = 1000;
    private static final int MAX_MESSAGES = 500;
    private static final Path CLIENT_ROOT = Paths.get(System.getenv().getOrDefault("TLC_CLIENT_DIR", "Client")).toAbsolutePath().normalize();
    private static final List<Map<String, String>> MESSAGES = new CopyOnWriteArrayList<>();
    private static final Pattern JSON_FIELD = Pattern.compile(
            "\\\"(sender|text)\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");

    private static AccountApi accountApi;

    private TlcServer() { }

    private static int readPort() {
        String value = System.getenv().getOrDefault("TLC_PORT", "8080");
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) throw new IllegalArgumentException();
            return port;
        } catch (IllegalArgumentException ex) {
            throw new ExceptionInInitializerError("TLC_PORT must be an integer from 1 to 65535");
        }
    }

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        String jdbcUrl = System.getenv().getOrDefault("TLC_DATABASE_URL", "jdbc:sqlite:tlc.db");
        try {
            accountApi = new AccountApi(server, jdbcUrl);
        } catch (Exception ex) {
            server.stop(0);
            throw new IOException("TLC account services failed to initialize; refusing to start unprotected", ex);
        }

        server.createContext("/api/status", exchange -> {
            cors(exchange);
            if (preflight(exchange)) return;
            if (!method(exchange, "GET")) return;
            send(exchange, 200,
                    "{\"status\":\"TLC Java connected\",\"version\":\"account-gated\"}",
                    "application/json; charset=utf-8");
        });
        server.createContext("/api/messages", TlcServer::handleMessages);
        server.createContext("/", TlcServer::serveClient);

        server.setExecutor(null);
        server.start();
        System.out.println("TLC listening on port " + PORT + " (client + approved-account API; client root " + CLIENT_ROOT + ")");
    }

    /** Serve only files inside Client/, preventing traversal and keeping API paths separate. */
    private static void serveClient(HttpExchange exchange) throws IOException {
        cors(exchange);
        if (preflight(exchange)) return;
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD, OPTIONS");
            send(exchange, 405, "Method not allowed", "text/plain; charset=utf-8");
            return;
        }
        String rawPath = exchange.getRequestURI().getPath();
        if (rawPath == null || rawPath.equals("/")) rawPath = "/index.html";
        String relative = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        Path file = CLIENT_ROOT.resolve(relative).normalize();
        if (!file.startsWith(CLIENT_ROOT) || !Files.isRegularFile(file)) {
            send(exchange, 404, "Not found", "text/plain; charset=utf-8");
            return;
        }
        String type = contentType(file);
        byte[] bytes;
        try { bytes = Files.readAllBytes(file); }
        catch (IOException ex) { send(exchange, 500, "Unable to read client file", "text/plain; charset=utf-8"); return; }
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        } else {
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
        }
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static void cors(HttpExchange exchange) {
        String allowedOrigin = System.getenv("TLC_ALLOWED_ORIGIN");
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (allowedOrigin != null && !allowedOrigin.isBlank() && allowedOrigin.equals(origin)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", allowedOrigin);
            exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, HEAD, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().set("Vary", "Origin");
    }

    private static boolean preflight(HttpExchange exchange) throws IOException {
        if (!"OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) return false;
        exchange.getResponseHeaders().set("Allow", "GET, POST, HEAD, OPTIONS");
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
        return true;
    }

    private static boolean method(HttpExchange exchange, String expected) throws IOException {
        if (expected.equalsIgnoreCase(exchange.getRequestMethod())) return true;
        exchange.getResponseHeaders().set("Allow", expected + ", OPTIONS");
        send(exchange, 405, "{\"error\":\"Method not allowed\"}", "application/json; charset=utf-8");
        return false;
    }

    private static void handleMessages(HttpExchange exchange) throws IOException {
        cors(exchange);
        if (preflight(exchange)) return;
        String requestMethod = exchange.getRequestMethod();
        boolean reading = "GET".equalsIgnoreCase(requestMethod);
        boolean posting = "POST".equalsIgnoreCase(requestMethod);
        if (!reading && !posting) {
            exchange.getResponseHeaders().set("Allow", "GET, POST, OPTIONS");
            send(exchange, 405, "{\"error\":\"Method not allowed\"}", "application/json; charset=utf-8");
            return;
        }
        if (accountApi == null) {
            send(exchange, 503, "{\"error\":\"Account service unavailable\"}", "application/json; charset=utf-8");
            return;
        }
        String username = accountApi.approvedUsername(exchange);
        if (username == null) return;
        if (reading) {
            send(exchange, 200, messagesJson(), "application/json; charset=utf-8");
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            send(exchange, 413, "{\"error\":\"Message too large\"}", "application/json; charset=utf-8");
            return;
        }
        String text = extractText(new String(body, StandardCharsets.UTF_8));
        if (text == null || text.trim().isEmpty() || text.length() > MAX_MESSAGE_CHARS) {
            send(exchange, 400, "{\"error\":\"Provide text (1-1000 characters)\"}", "application/json; charset=utf-8");
            return;
        }
        Map<String, String> message = new LinkedHashMap<>();
        message.put("sender", username);
        message.put("text", text.trim());
        message.put("time", Instant.now().toString());
        MESSAGES.add(message);
        while (MESSAGES.size() > MAX_MESSAGES) MESSAGES.remove(0);
        send(exchange, 201, "{\"ok\":true}", "application/json; charset=utf-8");
    }

    private static String extractText(String json) {
        Matcher matcher = JSON_FIELD.matcher(json);
        String text = null;
        while (matcher.find()) if ("text".equals(matcher.group(1))) text = unescapeJsonString(matcher.group(2));
        return text;
    }

    private static String messagesJson() {
        StringBuilder json = new StringBuilder("{\"messages\":[");
        for (int i = 0; i < MESSAGES.size(); i++) {
            if (i > 0) json.append(',');
            Map<String, String> message = MESSAGES.get(i);
            json.append("{\"sender\":\"").append(escapeJsonString(message.get("sender")))
                    .append("\",\"text\":\"").append(escapeJsonString(message.get("text")))
                    .append("\",\"time\":\"").append(escapeJsonString(message.get("time"))).append("\"}");
        }
        return json.append("]}").toString();
    }

    private static String escapeJsonString(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"': escaped.append("\\\""); break;
                case '\\': escaped.append("\\\\"); break;
                case '\b': escaped.append("\\b"); break;
                case '\f': escaped.append("\\f"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default: if (ch < 0x20) escaped.append(String.format("\\u%04x", (int) ch)); else escaped.append(ch);
            }
        }
        return escaped.toString();
    }

    private static String unescapeJsonString(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch != '\\' || i + 1 >= value.length()) { decoded.append(ch); continue; }
            char escaped = value.charAt(++i);
            switch (escaped) {
                case '"': decoded.append('"'); break;
                case '\\': decoded.append('\\'); break;
                case '/': decoded.append('/'); break;
                case 'b': decoded.append('\b'); break;
                case 'f': decoded.append('\f'); break;
                case 'n': decoded.append('\n'); break;
                case 'r': decoded.append('\r'); break;
                case 't': decoded.append('\t'); break;
                case 'u':
                    if (i + 4 < value.length()) {
                        try { decoded.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16)); i += 4; }
                        catch (NumberFormatException ex) { decoded.append('u'); }
                    } else decoded.append('u');
                    break;
                default: decoded.append(escaped);
            }
        }
        return decoded.toString();
    }

    private static void send(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
