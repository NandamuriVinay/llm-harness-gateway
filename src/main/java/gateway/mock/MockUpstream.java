package gateway.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class MockUpstream {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(9999), 0);
        server.createContext("/v1/chat/completions", MockUpstream::handleChat);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("Mock upstream server started on port 9999");
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
            System.out.println("Received request: " + requestBody);

            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0); // 0 = chunked streaming

            try (OutputStream outputStream = exchange.getResponseBody()) {
                // Initial prefill / TTFT delay (400ms)
                Thread.sleep(400);

                String[] words = { "Hello", "!", " I", " am", " a", " mock", " resilient", " LLM", " gateway",
                        " upstream", "." };
                for (String word : words) {
                    String chunk = String.format("data: {\"choices\":[{\"delta\":{\"content\":\"%s\"}}]}\n\n", word);
                    outputStream.write(chunk.getBytes(StandardCharsets.UTF_8));
                    outputStream.flush(); // Push immediately to client
                    Thread.sleep(50); // Inter-token generation delay (50ms)
                }

                outputStream.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }
        } catch (Exception e) {
            System.err.println("Error handling request: " + e.getMessage());
        }
    }
}
