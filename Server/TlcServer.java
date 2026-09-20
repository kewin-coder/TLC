import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Minimal TLC HTTP server starter. Keep it on a trusted LAN. */
public final class TlcServer {
    private static final int PORT = 8080;

    private TlcServer() {}

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/status", TlcServer::status);
        server.createContext("/", TlcServer::homeHint);
        server.setExecutor(null);
        server.start();
        System.out.println("TLC server listening on port " + PORT);
        System.out.println("Status: http://localhost:" + PORT + "/api/status");
    }

    private static void status(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            send(exchange, 405, "{\"error\":\"Method not allowed\"}", "application/json; charset=utf-8");
            return;
        }
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        send(exchange, 200, "{\"status\":\"TLC Java connected\",\"version\":\"starter\"}", "application/json; charset=utf-8");
    }

    private static void homeHint(HttpExchange exchange) throws IOException {
        send(exchange, 200, "TLC server is running. Check /api/status.", "text/plain; charset=utf-8");
    }

    private static void send(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
