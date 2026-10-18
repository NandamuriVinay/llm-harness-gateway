package gateway;

import gateway.proxy.CircuitBreaker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CircuitBreakerTest {

    @Test
    void testInitialStateIsClosed() {
        CircuitBreaker cb = new CircuitBreaker(3, 1000);
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.allowPrimaryCall());
    }

    @Test
    void testTripsToOpenAfterThreshold() {
        CircuitBreaker cb = new CircuitBreaker(3, 1000);

        cb.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.allowPrimaryCall());

        cb.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.allowPrimaryCall());

        // 3rd failure reaches threshold -> Trips to OPEN!
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.allowPrimaryCall());
    }

    @Test
    void testCooldownTransitionsToHalfOpenAndCanaryRecovery() throws InterruptedException {
        // 50ms cooldown for fast test execution
        CircuitBreaker cb = new CircuitBreaker(2, 50);

        cb.recordFailure();
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.allowPrimaryCall());

        // Wait for cooldown to expire
        Thread.sleep(60);

        // State should automatically transition to HALF_OPEN
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());
        assertTrue(cb.allowPrimaryCall()); // Canary probe allowed!

        // Canary probe succeeds -> Recovers to CLOSED
        cb.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertEquals(0, cb.getConsecutiveFailures());
    }

    @Test
    void testCanaryFailureRetripsToOpen() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(2, 50);

        cb.recordFailure();
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        Thread.sleep(60);
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());

        // Canary probe fails -> Immediately retrips to OPEN
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.allowPrimaryCall());
    }
}
