package gateway.limiter;

public class TokenBucket {

    private final double capacity;
    private final double refillRatePerSecond;
    private double currentTokens;
    private long lastRefillNanos;

    public TokenBucket(double capacity, double refillPerMinute) {
        this.capacity = capacity;
        this.currentTokens = capacity;
        this.refillRatePerSecond = refillPerMinute / 60.0;
        this.lastRefillNanos = System.nanoTime();
    }

    public synchronized boolean tryConsume(double tokens) {
        refill();

        if (currentTokens >= tokens) {
            currentTokens -= tokens;
            return true;
        }
        return false;
    }

    public synchronized void refund(double tokens) {
        currentTokens = Math.min(capacity, currentTokens + tokens);
    }

    public synchronized double getAvailableTokens() {
        refill();
        return currentTokens;
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSec > 0) {
            currentTokens = Math.min(capacity, currentTokens + (elapsedSec * refillRatePerSecond));
            lastRefillNanos = now;
        }
    }
}
