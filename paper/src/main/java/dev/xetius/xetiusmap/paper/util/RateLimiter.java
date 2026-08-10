package dev.xetius.xetiusmap.paper.util;

/**
 * Token bucket with a one-second window. Guards the per-player upload and request budgets so one
 * client cannot monopolise the store thread.
 *
 * <p>Not thread-safe on its own; each instance belongs to a single {@code PlayerSession} and is
 * only touched from the packet-handling thread.
 */
public final class RateLimiter {

    private double capacity;
    private double tokens;
    private double refillPerNano;
    private long lastRefillNanos;

    public RateLimiter(int perSecond) {
        setRate(perSecond);
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    public void setRate(int perSecond) {
        this.capacity = Math.max(1, perSecond);
        this.refillPerNano = capacity / 1_000_000_000.0;
        this.tokens = Math.min(tokens, capacity);
    }

    /** @return true if a token was available and consumed */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    public boolean tryAcquire(int permits) {
        refill();
        if (tokens >= permits) {
            tokens -= permits;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        if (elapsed <= 0) {
            return;
        }
        lastRefillNanos = now;
        tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
    }
}
