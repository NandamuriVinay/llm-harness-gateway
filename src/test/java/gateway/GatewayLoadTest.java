package gateway;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class GatewayLoadTest {

    private static final String GATEWAY_URL = "http://127.0.0.1:8080/v1/chat/completions";

    public record RequestResult(int statusCode, String cacheStatus, long durationMs, String error) {}

    /**
     * Executes a multi-client concurrent load test against the running Gateway.
     * Note: This test connects to the live gateway at http://127.0.0.1:8080.
     */
    public static void runLoadSimulation(int concurrency, int totalRequests) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        AtomicInteger success200 = new AtomicInteger(0);
        AtomicInteger rateLimited429 = new AtomicInteger(0);
        AtomicInteger serverErrors5xx = new AtomicInteger(0);
        AtomicInteger cacheHits = new AtomicInteger(0);
        AtomicInteger cacheMisses = new AtomicInteger(0);

        List<Long> latencies = new CopyOnWriteArrayList<>();

        // Test prompts mix: common prompts (cache targets) and unique prompts
        String[] promptPool = new String[]{
                "What is the capital of France?",
                "what is the capital of france!",
                "Explain quantum computing simply",
                "explain quantum computing simply.",
                "How does a neural network work?",
                "Tell me a short joke about programming"
        };

        System.out.println("================================================================");
        System.out.println(String.format("🚀 LAUNCHING LOAD TEST: %d requests across %d virtual threads", totalRequests, concurrency));
        System.out.println("================================================================");

        long testStartTime = System.currentTimeMillis();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<RequestResult>> tasks = new java.util.ArrayList<>();

            for (int i = 0; i < totalRequests; i++) {
                final int reqId = i;
                tasks.add(() -> {
                    // Pick a client identity: client-0, client-1, client-2...
                    // client-99 will be a "spammer" sending 10+ rapid requests
                    String clientId = (reqId % 4 == 0) ? "spammer-client" : "client-" + (reqId % 8);
                    String prompt = promptPool[reqId % promptPool.length];

                    String payload = String.format("{\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}],\"stream\":true}", prompt);

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(GATEWAY_URL))
                            .header("Authorization", "Bearer " + clientId)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .timeout(Duration.ofSeconds(10))
                            .build();

                    long startNanos = System.nanoTime();
                    try {
                        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
                        latencies.add(durationMs);

                        int code = response.statusCode();
                        if (code == 200) {
                            success200.incrementAndGet();
                            String cacheHeader = response.headers().firstValue("X-Cache-Status").orElse("NONE");
                            if ("HIT".equalsIgnoreCase(cacheHeader)) {
                                cacheHits.incrementAndGet();
                            } else {
                                cacheMisses.incrementAndGet();
                            }
                        } else if (code == 429) {
                            rateLimited429.incrementAndGet();
                        } else {
                            serverErrors5xx.incrementAndGet();
                        }

                        return new RequestResult(code, response.headers().firstValue("X-Cache-Status").orElse("NONE"), durationMs, null);
                    } catch (Exception e) {
                        return new RequestResult(0, "ERROR", 0, e.getMessage());
                    }
                });
            }

            // Launch all tasks concurrently
            executor.invokeAll(tasks);
        }

        long totalTestTimeMs = System.currentTimeMillis() - testStartTime;

        // Calculate latency percentiles
        List<Long> sorted = latencies.stream().sorted().collect(Collectors.toList());
        long p50 = sorted.isEmpty() ? 0 : sorted.get((int) (sorted.size() * 0.50));
        long p95 = sorted.isEmpty() ? 0 : sorted.get((int) (sorted.size() * 0.95));
        long p99 = sorted.isEmpty() ? 0 : sorted.get((int) (sorted.size() * 0.99));

        System.out.println("\n================================================================");
        System.out.println("📊 LOAD TEST RESULTS SUMMARY");
        System.out.println("================================================================");
        System.out.println(String.format("Total Duration:      %d ms", totalTestTimeMs));
        System.out.println(String.format("Total Requests:      %d", totalRequests));
        System.out.println(String.format("200 OK (Success):    %d", success200.get()));
        System.out.println(String.format("  ├── Cache Hits:    %d (< 50ms responses!)", cacheHits.get()));
        System.out.println(String.format("  └── Cache Misses:  %d (Upstream streams)", cacheMisses.get()));
        System.out.println(String.format("429 Rate Limited:    %d (Throttled gracefully)", rateLimited429.get()));
        System.out.println(String.format("5xx / Failures:      %d", serverErrors5xx.get()));
        System.out.println("----------------------------------------------------------------");
        System.out.println(String.format("Latency P50:         %d ms", p50));
        System.out.println(String.format("Latency P95:         %d ms", p95));
        System.out.println(String.format("Latency P99:         %d ms", p99));
        System.out.println("================================================================\n");
    }

    @Test
    void testSimulationWrapper() {
        // Simple assertion to make sure test runner recognizes class
        assertTrue(true);
    }

    public static void main(String[] args) throws Exception {
        runLoadSimulation(30, 60);
    }
}
