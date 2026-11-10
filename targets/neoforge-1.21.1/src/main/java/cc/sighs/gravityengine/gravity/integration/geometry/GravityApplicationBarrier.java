package cc.sighs.gravityengine.gravity.integration.geometry;

import net.minecraft.world.entity.Entity;

import java.util.Objects;

/**
 * Invocation-local exclusion of application/geometry handoffs.
 * Does not hold a frame, collision result, position, or packet decision.
 * Closing only releases the barrier; the ordinary lifecycle retries pending work.
 */
public final class GravityApplicationBarrier {
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();

    private GravityApplicationBarrier() {}

    public static Scope hold(Entity entity) {
        Scope scope = new Scope(
                Objects.requireNonNull(entity, "entity"),
                ACTIVE.get(),
                Thread.currentThread()
        );
        ACTIVE.set(scope);
        return scope;
    }

    public static boolean isHeld(Entity entity) {
        for (Scope scope = ACTIVE.get(); scope != null; scope = scope.parent) {
            if (scope.entity == entity) {
                return true;
            }
        }
        return false;
    }

    public static final class Scope implements AutoCloseable {
        private final Entity entity;
        private final Scope parent;
        private final Thread owner;
        private boolean closed;

        private Scope(Entity entity, Scope parent, Thread owner) {
            this.entity = entity;
            this.parent = parent;
            this.owner = owner;
        }

        @Override
        public void close() {
            if (closed || Thread.currentThread() != owner || ACTIVE.get() != this) {
                throw new IllegalStateException(
                        "application barriers must close once, in LIFO order, on their owner thread"
                );
            }
            closed = true;
            if (parent == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(parent);
            }
        }
    }
}
