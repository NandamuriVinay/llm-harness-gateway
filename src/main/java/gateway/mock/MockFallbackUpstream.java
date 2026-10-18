package gateway.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class MockFallbackUpstream {

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(9998), 0);
        server.createContext("/v1/chat/completions", MockFallbackUpstream::handleChat);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("Mock Fallback upstream server started on port 9998");
    }

    private static void handleChat(HttpExchange exchange) {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }

            InputStream inputStream = exchange.getRequestBody();
            String requestBody = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("[Fallback-9998] Received request: " + requestBody);

            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.getResponseHeaders().set("X-Upstream-Provider", "fallback-9998");
            exchange.sendResponseHeaders(200, 0); // chunked streaming

            try (OutputStream outputStream = exchange.getResponseBody()) {
                // Modest prefill delay (150ms)
                Thread.sleep(150);

                String[] words = {"[FALLBACK]", " Response", " from", " secondary", " resilient", " provider", "."};
                for (String word : words) {
                    String chunk = String.format("data: {\"choices\":[{\"delta\":{\"content\":\"%s\"}}]}\n\n", word);
                    outputStream.write(chunk.getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                    Thread.sleep(30);
                }

                outputStream.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }
        } catch (Exception e) {
            System.err.println("[Fallback-9998] Error handling request: " + e.getMessage());
        }
    }
}
