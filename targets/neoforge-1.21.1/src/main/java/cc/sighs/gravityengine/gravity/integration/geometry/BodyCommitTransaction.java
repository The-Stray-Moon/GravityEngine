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

    /** Capture before any body write. All restore attempts run even if one callback fails. */
    public static java.util.function.Consumer<Throwable> captureRollback(Entity entity) {
        var access = cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess.cast(entity);
        var component = access.gravityengine$gravityComponent();
        var runtime = component.operationState();
        var stateRestore = component.state().captureBodyRollback();
        var runtimeRestore = runtime.captureBodyRollback();
        var dimensionsRestore = access.gravityengine$captureDimensionsRollback();
        var anchor = entity.position();
        var box = entity.getBoundingBox();
        var up = runtime.installedCollisionUp();
        var collision = access.gravityengine$getVanillaCollisionState();
        var supportingBlock = access.gravityengine$getVanillaSupportingBlock().orElse(null);
        var velocity = entity.getDeltaMovement();
        float fallDistance = entity.fallDistance;
        Runnable[] restores = {
                dimensionsRestore,
                () -> cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry
                        .restoreExactGeometry(entity, anchor, box, up),
                () -> access.gravityengine$restoreVanillaCollisionState(collision),
                () -> access.gravityengine$setVanillaSupportingBlock(supportingBlock),
                () -> { entity.setDeltaMovement(velocity); entity.fallDistance = fallDistance; },
                stateRestore, runtimeRestore
        };
        return failure -> {
            for (Runnable restore : restores) {
                try { restore.run(); }
                catch (RuntimeException | Error rollbackFailure) {
                    if (rollbackFailure != failure) failure.addSuppressed(rollbackFailure);
                }
            }
        };
    }

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
