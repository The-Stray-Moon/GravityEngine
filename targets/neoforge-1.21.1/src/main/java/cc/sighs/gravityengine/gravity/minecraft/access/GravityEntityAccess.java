package cc.sighs.gravityengine.gravity.minecraft.access;

import cc.sighs.gravityengine.gravity.collision.GravityMoveResult;
import cc.sighs.gravityengine.gravity.component.EntityGravityComponent;
import cc.sighs.gravityengine.gravity.runtime.VanillaCollisionState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.util.Optional;

public interface GravityEntityAccess {
    static GravityEntityAccess cast(Entity entity) {
        return (GravityEntityAccess) entity;
    }

    EntityGravityComponent gravityengine$gravityComponent();

    /** Actual dimensions installed by Vanilla/NeoForge Size, not a pose request. */
    net.minecraft.world.entity.EntityDimensions gravityengine$installedDimensions();

    /**
     * Thin read-only view of the entity's own persisted vanilla supporting
     * block. It exposes an existing {@code Entity} field only; it never stores
     * a second copy in the mod runtime.
     */
    Optional<BlockPos> gravityengine$getVanillaSupportingBlock();

    /**
     * Transaction restore seam for the vanilla supporting-block witness.
     *
     * <p>{@code null} restores the absent state.  Only geometry transactions
     * that captured the witness may write it back; ordinary gameplay never
     * sets it here.</p>
     */
    void gravityengine$setVanillaSupportingBlock(BlockPos supportingBlock);

    /**
     * Transaction snapshot of the vanilla ground/collision fields.
     *
     * <p>Custom movement translates the authoritative collision facts into
     * these Vanilla fields; the snapshot preserves the exact pre-write state
     * for the movement seams that must keep Vanilla's field semantics.</p>
     */
    VanillaCollisionState gravityengine$getVanillaCollisionState();

    /**
     * Transaction restore seam for the vanilla ground/collision fields.
     * Only the move transaction that captured the state may write it back.
     */
    void gravityengine$restoreVanillaCollisionState(
            VanillaCollisionState state
    );

    GravityMoveResult gravityengine$getCurrentMoveResult();
    void gravityengine$setCurrentMoveResult(GravityMoveResult result);
}
