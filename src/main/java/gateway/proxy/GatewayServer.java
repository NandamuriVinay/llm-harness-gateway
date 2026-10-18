package gateway.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gateway.cache.SemanticCache;
import gateway.limiter.RateLimiter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;

public class GatewayServer {

    private static final String PRIMARY_URL = "http://127.0.0.1:9999/v1/chat/completions";
    private static final String FALLBACK_URL = "http://127.0.0.1:9998/v1/chat/completions";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Dual-Tier Rate Limiter:
    // Capacity 5 requests burst (refill 10/min) | Capacity 300 tokens burst (refill
    // 600/min)
    private static final RateLimiter RATE_LIMITER = new RateLimiter(5.0, 10.0, 300.0, 600.0);

    // Semantic Cache: in-memory vector similarity index (0.95 threshold)
    private static final SemanticCache SEMANTIC_CACHE = new SemanticCache();
    private static final double CACHE_SIMILARITY_THRESHOLD = 0.95;

    // Adaptive Circuit Breaker: Trips after 3 consecutive failures, 5-second
    // cooldown
    private static final CircuitBreaker CIRCUIT_BREAKER = new CircuitBreaker(3, 5000);

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/v1/chat/completions", GatewayServer::handleProxy);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("Gateway server started on port 8080");
        System.out.println(" -> Primary:  " + PRIMARY_URL);
        System.out.println(" -> Fallback: " + FALLBACK_URL);
    }

    private static void handleProxy(HttpExchange exchange) {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }

            // 1. Identify Client (Bearer token or client IP address)
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            String clientId = (authHeader != null && !authHeader.isBlank())
                    ? authHeader.replace("Bearer ", "").trim()
                    : exchange.getRemoteAddress().getAddress().getHostAddress();

            // 2. Read incoming client request body
            byte[] requestBodyBytes = exchange.getRequestBody().readAllBytes();
            String requestBody = new String(requestBodyBytes, StandardCharsets.UTF_8);

            // 3. Fast token estimation (1 token ≈ 4 characters)
            int estimatedTokens = Math.max(1, requestBody.length() / 4);

            // 4. Rate Limiter Gate (RPM & TPM Check)
            if (!RATE_LIMITER.allowRequest(clientId, estimatedTokens)) {
                System.out.println("[Gateway] 🚨 429 RATE LIMIT EXCEEDED for [" + clientId +
                        "] | Requested tokens: " + estimatedTokens);

                byte[] errResp = ("{\"error\":{\"message\":\"Rate limit exceeded (RPM/TPM). Please retry later.\"," +
                        "\"type\":\"rate_limit_exceeded\",\"code\":429}}\n").getBytes(StandardCharsets.UTF_8);

                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Retry-After", "5");
                exchange.sendResponseHeaders(429, errResp.length);

                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(errResp);
                    os.flush();
                }
                return;
            }

            // 5. Extract user prompt for semantic cache
            String userPrompt = extractUserPrompt(requestBody);

            // 6. Semantic Cache Gate (Cosine Similarity ANN search)
            Optional<SemanticCache.CacheHit> cacheHit = SEMANTIC_CACHE.findSimilar(userPrompt,
                    CACHE_SIMILARITY_THRESHOLD);
            if (cacheHit.isPresent()) {
                SemanticCache.CacheHit hit = cacheHit.get();
                System.out.println(String.format(
                        "[Gateway] ⚡ CACHE HIT! (Similarity: %.4f) Matched: \"%s\" | Replaying %d chunks in < 5ms",
                        hit.similarity(), hit.matchedPrompt(), hit.sseChunks().size()));

                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                exchange.getResponseHeaders().set("Connection", "keep-alive");
                exchange.getResponseHeaders().set("X-Cache-Status", "HIT");
                exchange.sendResponseHeaders(200, 0); // chunked SSE

                try (OutputStream clientOut = exchange.getResponseBody()) {
                    for (String chunk : hit.sseChunks()) {
                        clientOut.write(chunk.getBytes(StandardCharsets.UTF_8));
                        clientOut.flush();
                    }
                }
                return;
            }

            // 7. Cache Miss: Route request via Circuit Breaker
            boolean attemptingPrimary = CIRCUIT_BREAKER.allowPrimaryCall();
            String targetUrl = attemptingPrimary ? PRIMARY_URL : FALLBACK_URL;
            String routedProvider = attemptingPrimary ? "primary" : "fallback";

            if (!attemptingPrimary) {
                System.out.println(
                        "[Gateway] 🔴 Circuit Breaker is OPEN -> Diverting directly to Fallback: " + FALLBACK_URL);
            }

            long startTimeNanos = System.nanoTime();
            HttpResponse<InputStream> upstreamResp = null;

            try {
                HttpRequest upstreamReq = HttpRequest.newBuilder()
                        .uri(URI.create(targetUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(requestBodyBytes))
                        .timeout(Duration.ofSeconds(2))
                        .build();

                upstreamResp = HTTP_CLIENT.send(upstreamReq, HttpResponse.BodyHandlers.ofInputStream());

                // Check for HTTP errors that trip the breaker
                if (attemptingPrimary && (upstreamResp.statusCode() >= 500 || upstreamResp.statusCode() == 429)) {
                    throw new IOException("Primary returned status " + upstreamResp.statusCode());
                }

                if (attemptingPrimary) {
                    CIRCUIT_BREAKER.recordSuccess();
                }

            } catch (Exception e) {
                if (attemptingPrimary) {
                    System.err.println("[Gateway] ⚠️ Primary failed (" + e.getMessage() + "). Tripping check...");
                    CIRCUIT_BREAKER.recordFailure();

                    // Automatic Failover to Fallback Provider
                    try {
                        System.out.println(
                                "[Gateway] 🔄 Dynamic failover -> Routing request to Fallback: " + FALLBACK_URL);
                        HttpRequest fallbackReq = HttpRequest.newBuilder()
                                .uri(URI.create(FALLBACK_URL))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBodyBytes))
                                .timeout(Duration.ofSeconds(2))
                                .build();

                        upstreamResp = HTTP_CLIENT.send(fallbackReq, HttpResponse.BodyHandlers.ofInputStream());
                        routedProvider = "fallback";

                    } catch (Exception fallbackErr) {
                        System.err.println(
                                "[Gateway] ❌ Both Primary and Fallback providers failed: " + fallbackErr.getMessage());
                        sendServiceUnavailable(exchange);
                        return;
                    }
                } else {
                    System.err.println("[Gateway] ❌ Fallback provider failed: " + e.getMessage());
                    sendServiceUnavailable(exchange);
                    return;
                }
            }

            // 8. Set response headers matching upstream
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.getResponseHeaders().set("X-Cache-Status", "MISS");
            exchange.getResponseHeaders().set("X-Routed-Provider", routedProvider);
            exchange.sendResponseHeaders(upstreamResp.statusCode(), 0); // 0 = chunked streaming

            // 9. Pipe upstream stream to client, instrument metrics, and capture chunks
            StreamInterceptor.StreamResult result = StreamInterceptor.pipeAndInstrument(
                    upstreamResp.body(),
                    exchange.getResponseBody(),
                    startTimeNanos);

            System.out.println("[Gateway] (" + routedProvider + ") " + result.metrics());

            // 10. Populate Semantic Cache with captured chunks
            if (result.capturedChunks() != null && !result.capturedChunks().isEmpty()) {
                SEMANTIC_CACHE.put(userPrompt, result.capturedChunks());
            }

        } catch (Exception e) {
            System.err.println("[Gateway] Unhandled error: " + e.getMessage());
            sendServiceUnavailable(exchange);
        }
    }

    private static void sendServiceUnavailable(HttpExchange exchange) {
        try {
            byte[] errResp = "{\"error\":{\"message\":\"All upstream LLM providers are unavailable.\",\"code\":503}}\n"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(503, errResp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(errResp);
                os.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private static String extractUserPrompt(String jsonBody) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(jsonBody);
            JsonNode messages = root.get("messages");
            if (messages != null && messages.isArray() && !messages.isEmpty()) {
                for (int i = messages.size() - 1; i >= 0; i--) {
                    JsonNode msg = messages.get(i);
                    if ("user".equalsIgnoreCase(msg.path("role").asText())) {
                        return msg.path("content").asText();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return jsonBody;
    }
}
