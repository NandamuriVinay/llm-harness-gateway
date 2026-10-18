package gateway;

import gateway.limiter.RateLimiter;
import gateway.limiter.TokenBucket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RateLimiterTest {

    @Test
    void testTokenBucketBasicConsumption() {
        TokenBucket bucket = new TokenBucket(3.0, 60.0); // 3 capacity, 60/min (1/sec)

        assertTrue(bucket.tryConsume(1.0));
        assertTrue(bucket.tryConsume(1.0));
        assertTrue(bucket.tryConsume(1.0));

        // Exhausted!
        assertFalse(bucket.tryConsume(1.0));
    }

    @Test
    void testTokenBucketRefund() {
        TokenBucket bucket = new TokenBucket(2.0, 60.0);

        assertTrue(bucket.tryConsume(1.0));
        assertTrue(bucket.tryConsume(1.0));
        assertFalse(bucket.tryConsume(1.0)); // empty

        // Refund 1 token
        bucket.refund(1.0);
        assertTrue(bucket.tryConsume(1.0)); // now succeeds!
    }

    @Test
    void testDualTierRpmRejection() {
        // 2 RPM burst, 500 TPM burst
        RateLimiter limiter = new RateLimiter(2.0, 2.0, 500.0, 500.0);

        assertTrue(limiter.allowRequest("user1", 10));
        assertTrue(limiter.allowRequest("user1", 10));

        // 3rd request should exceed RPM capacity
        assertFalse(limiter.allowRequest("user1", 10));
    }

    @Test
    void testDualTierTpmRejectionAndRefund() {
        // 5 RPM burst, 100 TPM burst
        RateLimiter limiter = new RateLimiter(5.0, 5.0, 100.0, 100.0);

        assertTrue(limiter.allowRequest("user1", 40));
        assertTrue(limiter.allowRequest("user1", 40));

        // 3rd request asks for 50 tokens (40 + 40 + 50 = 130 > 100), exceeds TPM!
        assertFalse(limiter.allowRequest("user1", 50));

        // Verify compensating action: RPM token was refunded, so a small request still
        // fits TPM and RPM!
        assertTrue(limiter.allowRequest("user1", 10));
    }

    @Test
    void testPerClientIsolation() {
        // 2 RPM burst per client
        RateLimiter limiter = new RateLimiter(2.0, 2.0, 500.0, 500.0);

        // Exhaust user-alice
        assertTrue(limiter.allowRequest("alice", 10));
        assertTrue(limiter.allowRequest("alice", 10));
        assertFalse(limiter.allowRequest("alice", 10)); // Alice is rate limited

        // User-bob is NOT affected!
        assertTrue(limiter.allowRequest("bob", 10));
        assertTrue(limiter.allowRequest("bob", 10));
        assertFalse(limiter.allowRequest("bob", 10));
    }
}
