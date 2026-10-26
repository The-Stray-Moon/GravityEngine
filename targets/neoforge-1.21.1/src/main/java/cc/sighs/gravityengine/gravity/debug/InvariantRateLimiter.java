package cc.sighs.gravityengine.gravity.debug;

import java.util.LinkedHashMap;

/** Small bounded LRU. One atomic check/update; elapsed time uses a monotonic clock. */
final class InvariantRateLimiter {
    private final int capacity;
    private final long intervalNanos;
    private final LinkedHashMap<String, Long> last = new LinkedHashMap<>(16, 0.75F, true);

    InvariantRateLimiter(int capacity, long intervalNanos) {
        if (capacity <= 0 || intervalNanos < 0) throw new IllegalArgumentException();
        this.capacity = capacity;
        this.intervalNanos = intervalNanos;
    }

    synchronized boolean acquire(String key, long now) {
        Long previous = last.get(key);
        if (previous != null && now - previous < intervalNanos) return false;
        last.put(key, now);
        if (last.size() > capacity) last.remove(last.keySet().iterator().next());
        return true;
    }

    synchronized int size() { return last.size(); }
}
