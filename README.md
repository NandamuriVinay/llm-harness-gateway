# LLM Harness Gateway 🛡️⚡

A resilient, high-throughput LLM reverse proxy and control plane built with **Java 21 Virtual Threads** and zero heavy frameworks.

This project treats Large Language Models as fragile, unpredictable, high-latency downstream microservices and constructs a deterministic operating system around them to enforce latency, fault tolerance, cost bounds, and concurrency isolation.

---

## Architecture Overview

```
Client Requests (SSE / POST /v1/chat/completions)
       │
       ▼
┌────────────────────────────────────────────────────────┐
│               REVERSE PROXY / HARNESS (:8080)          │
│  ├── 1. Dual-Tier Token Bucket (RPM & TPM Limiter)     │
│  ├── 2. Semantic Cache (128-D Vector Cosine ANN Search)│
│  ├── 3. Adaptive Circuit Breaker (CLOSED / OPEN / HALF)│
│  └── 4. Stream Interceptor (Zero-buffering TTFT & TPS) │
└────────────────────────────────────────────────────────┘
       │                                     │
       ▼ (Primary Healthy)                   ▼ (Dynamic Failover)
Primary LLM Provider (:9999)          Secondary Fallback Provider (:9998)
```

---

## Core Systems Features

1. **Zero-Buffering Streaming Proxy (Java 21 Virtual Threads):**
   Pipes Server-Sent Events (SSE) directly from upstream to client without buffering tokens in heap memory, preventing thread exhaustion even under high concurrency.
2. **Real-time Telemetry Tap:**
   Calculates Time-To-First-Token (TTFT) and Tokens-Per-Second (TPS) on the fly without introducing latency or deserializing the entire JSON payload.
3. **Dual-Tier Rate Limiting (RPM + TPM):**
   Distributed token bucket algorithm enforcing both Requests Per Minute and prompt context Tokens Per Minute. Employs a compensating rollback pattern if a multi-tier check fails, with per-client tenant isolation.
4. **Semantic Cache with Vector Similarity:**
   Employs an in-memory 128-dimensional n-gram hashing vectorizer and cosine similarity search ($\ge 0.95$ threshold). Cache hits replay SSE streams directly to clients in $< 10\text{ms}$ (a 44x latency reduction and \$0 upstream inference cost).
5. **Adaptive Circuit Breaker & Failover:**
   Tracks upstream 5xx errors, 429 rate limits, and network disconnects in a sliding window. Automatically trips from `CLOSED` to `OPEN` and dynamically diverts traffic to a secondary fallback provider. Periodically probes recovery via `HALF_OPEN` canary requests.

---

## Project Structure

```
llm-harness-gateway/
├── build.gradle.kts
├── settings.gradle.kts
├── src/
│   ├── main/java/gateway/
│   │   ├── proxy/
│   │   │   ├── GatewayServer.java        # Reverse proxy route handler (:8080)
│   │   │   ├── StreamInterceptor.java    # Zero-buffering SSE chunk parser & metrics tap
│   │   │   └── CircuitBreaker.java       # 3-state failure tracker & failover router
│   │   ├── cache/
│   │   │   ├── VectorUtils.java          # 128-D vector embedding & cosine similarity math
│   │   │   └── SemanticCache.java        # In-memory vector store & SSE stream replay
│   │   ├── limiter/
│   │   │   ├── TokenBucket.java          # Lazy-refill token bucket with refunds
│   │   │   └── RateLimiter.java          # Dual-tier RPM/TPM per-client rate limiter
│   │   ├── telemetry/
│   │   │   └── StreamMetrics.java        # TTFT, TPS, and latency value records
│   │   └── mock/
│   │       ├── MockUpstream.java         # Primary fake LLM with prefill latency (:9999)
│   │       └── MockFallbackUpstream.java # Secondary fallback provider (:9998)
│   └── test/java/gateway/
│       ├── RateLimiterTest.java          # Unit tests for token bucket & multi-tenancy
│       ├── SemanticCacheTest.java        # Unit tests for vector math & cache hits
│       ├── CircuitBreakerTest.java       # Unit tests for state machine & canary probes
│       └── GatewayLoadTest.java          # Virtual thread concurrent load simulation
```

---

## Quickstart & Running Locally

### Prerequisites
- Java 21+ (`java -version`)
- Gradle (`gradle --version`)

### 1. Run Unit Tests
```bash
gradle test
```

### 2. Start the Primary Mock Upstream (:9999)
```bash
gradle mockUpstream
```

### 3. Start the Secondary Fallback Provider (:9998)
```bash
gradle mockFallback
```

### 4. Start the Gateway Harness (:8080)
```bash
gradle gateway
```

### 5. Send a Streaming Request
```bash
curl -N -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H "Authorization: Bearer my-api-key" \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"What is the capital of France?"}],"stream":true}'
```

### 6. Run the Concurrent Load Simulation
In a separate terminal while the servers are running:
```bash
gradle loadTest
```
