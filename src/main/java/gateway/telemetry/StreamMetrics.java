package gateway.telemetry;

public record StreamMetrics(
        double ttftMs,
        double totalDurationMs,
        int totalTokens,
        double tokensPerSecond) {

    @Override
    public String toString() {
        return String.format(
                "Metrics -> TTFT: %.2f ms | Total: %.2f ms | Tokens: %d | TPS: %.2f",
                ttftMs, totalDurationMs, totalTokens, tokensPerSecond);
    }
}
