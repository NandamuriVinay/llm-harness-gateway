package gateway.proxy;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class CircuitBreaker {

    public enum State {
        CLOSED,     // Normal healthy operation: traffic routes to Primary
        OPEN,       // Primary failed: traffic diverts directly to Fallback
        HALF_OPEN   // Cooldown expired: canary probe checks if Primary has recovered
    }

    private final int failureThreshold;
    private final long cooldownMillis;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong lastTripTimeMillis = new AtomicLong(0);

    public CircuitBreaker(int failureThreshold, long cooldownMillis) {
        this.failureThreshold = failureThreshold;
        this.cooldownMillis = cooldownMillis;
    }

    /**
     * Determines the current state, handling automatic transition from OPEN to HALF_OPEN
     * once the cooldown window has elapsed.
     */
    public State getState() {
        State current = state.get();
        if (current == State.OPEN) {
            long elapsed = System.currentTimeMillis() - lastTripTimeMillis.get();
            if (elapsed >= cooldownMillis) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    System.out.println("[CircuitBreaker] 🟡 Cooldown elapsed (" + elapsed + "ms). Transitioning to HALF-OPEN (canary probe).");
                    return State.HALF_OPEN;
                }
            }
        }
        return state.get();
    }

    /**
     * Checks whether a request is allowed to attempt calling the Primary upstream.
     * Returns true if CLOSED or HALF_OPEN (canary probe).
     * Returns false if OPEN (divert directly to fallback without waiting on network timeouts).
     */
    public boolean allowPrimaryCall() {
        State current = getState();
        return current == State.CLOSED || current == State.HALF_OPEN;
    }

    /**
     * Records a successful upstream response.
     * Recovers from HALF_OPEN to CLOSED and resets consecutive failure counts.
     */
    public void recordSuccess() {
        State previous = state.getAndSet(State.CLOSED);
        consecutiveFailures.set(0);
        if (previous == State.HALF_OPEN) {
            System.out.println("[CircuitBreaker] 🟢 Canary probe SUCCEEDED! Primary recovered. Circuit is now CLOSED.");
        }
    }

    /**
     * Records an upstream error (5xx, 429, or network disconnect).
     * Trips from CLOSED to OPEN when the failure threshold is reached,
     * or trips immediately back to OPEN if a canary probe fails during HALF_OPEN.
     */
    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        State current = state.get();

        if (current == State.HALF_OPEN || failures >= failureThreshold) {
            state.set(State.OPEN);
            lastTripTimeMillis.set(System.currentTimeMillis());
            System.out.println("[CircuitBreaker] 🔴 Primary failure detected (failures=" + failures +
                    ", threshold=" + failureThreshold + "). TRIPPING CIRCUIT TO OPEN! All traffic routing to fallback.");
        } else {
            System.out.println("[CircuitBreaker] ⚠️ Primary error recorded (" + failures + "/" + failureThreshold + " until trip).");
        }
    }

    public void reset() {
        state.set(State.CLOSED);
        consecutiveFailures.set(0);
        lastTripTimeMillis.set(0);
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }
}
