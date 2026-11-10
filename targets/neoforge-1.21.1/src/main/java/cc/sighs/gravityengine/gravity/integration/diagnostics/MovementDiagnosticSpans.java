package cc.sighs.gravityengine.gravity.integration.diagnostics;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongFunction;

/**
 * Target-internal observation stacks. All operations, including lifecycle
 * invalidation, belong to the entity's Level thread. Different threads have
 * independent stores, even if accidentally given the same entity object.
 * Payloads MUST NOT retain the key, its Level, or any platform owner indirectly.
 * Sequence is per (thread, object identity), survives discontinuity, and resets
 * after clear. It is not a network sequence or a client/server correlation ID.
 */
final class MovementDiagnosticSpans<T> {
    private final ThreadLocal<Store<T>> local = new ThreadLocal<>();

    Token<T> begin(Object entity, LongFunction<T> payload) {
        Store<T> store = local.get();
        if (store == null) {
            store = new Store<>();
            local.set(store);
        }
        store.expunge();
        IdentityKey lookup = new IdentityKey(entity, null);
        State<T> state = store.states.get(lookup);
        if (state == null) {
            state = new State<>(new IdentityKey(entity, store.queue));
            store.states.put(state.key, state);
        }
        Token<T> token = new Token<>(state, payload.apply(++state.sequence));
        state.active = token;
        return token;
    }

    Token<T> active(Object entity) {
        State<T> state = find(entity);
        return state == null ? null : state.active;
    }

    void discontinuity(Object entity) {
        State<T> state = find(entity);
        if (state != null) state.invalidate();
    }

    void clear(Object entity) {
        Store<T> store = local.get();
        if (store == null) return;
        store.expunge();
        State<T> state = store.states.remove(new IdentityKey(entity, null));
        if (state != null) state.invalidate();
        if (store.states.isEmpty()) local.remove();
    }

    private State<T> find(Object entity) {
        Store<T> store = local.get();
        if (store == null) return null; // Reads/cleanup never allocate a store.
        store.expunge();
        return store.states.get(new IdentityKey(entity, null));
    }

    static final class Token<T> {
        private final State<T> state;
        private final Token<T> parent;
        private final long epoch;
        private final T value;
        private boolean closed;

        private Token(State<T> state, T value) {
            this.state = state;
            this.parent = state.active;
            this.epoch = state.epoch;
            this.value = value;
        }

        T value() { return value; }

        private boolean valid(Object entity) {
            // Check thread first: foreign callers never read mutable state.
            return state.thread == Thread.currentThread().threadId()
                    && state.key.refersTo(entity) && !closed && epoch == state.epoch;
        }

        boolean active(Object entity) {
            return valid(entity) && state.active == this;
        }

        /** False means no observation may be published. Out-of-order closure
         * retires only this token; the current child keeps ownership. */
        boolean finish(Object entity) {
            if (!valid(entity)) return false;
            closed = true;
            if (state.active != this) return false;
            Token<T> restored = parent;
            while (restored != null && restored.closed) restored = restored.parent;
            state.active = restored;
            return true;
        }
    }

    private static final class State<T> {
        final IdentityKey key;
        final long thread = Thread.currentThread().threadId();
        long sequence;
        long epoch;
        Token<T> active;

        State(IdentityKey key) { this.key = key; }

        void invalidate() {
            ++epoch;
            active = null;
        }
    }

    private static final class Store<T> {
        final ReferenceQueue<Object> queue = new ReferenceQueue<>();
        final Map<IdentityKey, State<T>> states = new HashMap<>();

        void expunge() {
            IdentityKey key;
            while ((key = (IdentityKey) queue.poll()) != null) {
                State<T> state = states.remove(key);
                if (state != null) state.invalidate();
            }
        }
    }

    private static final class IdentityKey extends WeakReference<Object> {
        private final int hash;

        IdentityKey(Object entity, ReferenceQueue<Object> queue) {
            super(entity, queue);
            hash = System.identityHashCode(entity);
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            Object entity = get();
            return entity != null && other instanceof IdentityKey key && key.refersTo(entity);
        }
    }
}
