package cc.sighs.gravityengine.gravity.integration.geometry;

import net.minecraft.world.entity.Entity;

/** Operation-local physical installation barrier. A callback may not start movement
 * against half-installed body facts. Contains no assignment, geometry or packet state. */
public final class BodyCommitTransaction implements AutoCloseable {
    private static final ThreadLocal<BodyCommitTransaction> ACTIVE = new ThreadLocal<>();
    private final Entity entity;
    private final BodyCommitTransaction parent;
    private final GravityApplicationBarrier.Scope barrier;

    private BodyCommitTransaction(Entity entity, boolean authoritative) {
        requireOutside(entity);
        if (authoritative && (entity.level().isClientSide()
                || !PlayerBodyHandoff.mayChangeBody(entity)
                || GravityApplicationBarrier.isHeld(entity))) {
            throw new IllegalStateException("server body installation requires the handoff boundary");
        }
        this.entity = entity;
        this.parent = ACTIVE.get();
        // The server owner must execute the application coordinator inside this
        // transaction. Movement is excluded by ACTIVE; no application barrier
        // is bypassed. Replica installation excludes application derivation too.
        this.barrier = authoritative ? null : GravityApplicationBarrier.hold(entity);
        ACTIVE.set(this);
    }

    public static BodyCommitTransaction begin(Entity entity) { return new BodyCommitTransaction(entity, false); }

    public static BodyCommitTransaction beginAuthoritative(net.minecraft.server.level.ServerPlayer player) {
        return new BodyCommitTransaction(player, true);
    }

    public static void requireOutside(Entity entity) {
        if (isActive(entity)) throw new IllegalStateException("physical operation during body installation");
    }

    public static boolean isActive(Entity entity) {
        for (var scope = ACTIVE.get(); scope != null; scope = scope.parent) {
            if (scope.entity == entity) return true;
        }
        return false;
    }

    @Override public void close() {
        if (ACTIVE.get() != this) throw new IllegalStateException("body transactions must close in LIFO order");
        if (barrier != null) barrier.close();
        if (parent == null) ACTIVE.remove(); else ACTIVE.set(parent);
    }
}
