package gateway.limiter;

import java.util.concurrent.ConcurrentHashMap;

public class RateLimiter {

    private record ClientBuckets(TokenBucket rpmBucket, TokenBucket tpmBucket) {
    }

    private final double rpmCapacity;
    private final double rpmRefillPerMin;
    private final double tpmCapacity;
    private final double tpmRefillPerMin;

    private final ConcurrentHashMap<String, ClientBuckets> clientLimiters = new ConcurrentHashMap<>();

    public RateLimiter(double rpmCapacity, double rpmRefillPerMin, double tpmCapacity, double tpmRefillPerMin) {
        this.rpmCapacity = rpmCapacity;
        this.rpmRefillPerMin = rpmRefillPerMin;
        this.tpmCapacity = tpmCapacity;
        this.tpmRefillPerMin = tpmRefillPerMin;
    }

    /**
     * Dual-tier rate limit check for a given client:
     * Tier 1: Check RPM (1 request)
     * Tier 2: Check TPM (estimatedTokens)
     * If TPM fails, refunds the RPM token (compensating action) so the client is
     * not penalized.
     */
    public boolean allowRequest(String clientId, int estimatedTokens) {
        ClientBuckets buckets = clientLimiters.computeIfAbsent(clientId, id -> new ClientBuckets(
                new TokenBucket(rpmCapacity, rpmRefillPerMin),
                new TokenBucket(tpmCapacity, tpmRefillPerMin)));

        // 1. Check RPM
        if (!buckets.rpmBucket.tryConsume(1)) {
            return false;
        }

        // 2. Check TPM
        if (!buckets.tpmBucket.tryConsume(estimatedTokens)) {
            // Compensating action: refund the consumed RPM token
            buckets.rpmBucket.refund(1);
            return false;
        }

        return true;
    }

    public double getAvailableRpm(String clientId) {
        ClientBuckets buckets = clientLimiters.get(clientId);
        return buckets != null ? buckets.rpmBucket.getAvailableTokens() : rpmCapacity;
    }

    public double getAvailableTpm(String clientId) {
        ClientBuckets buckets = clientLimiters.get(clientId);
        return buckets != null ? buckets.tpmBucket.getAvailableTokens() : tpmCapacity;
    }
}
