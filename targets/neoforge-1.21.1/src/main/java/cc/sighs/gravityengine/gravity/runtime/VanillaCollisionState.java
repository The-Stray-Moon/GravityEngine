package cc.sighs.gravityengine.gravity.runtime;

import net.minecraft.core.BlockPos;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable snapshot of the vanilla collision/ground fields of one entity.
 *
 * <p>This is the Vanilla API projection between the custom collision result
 * and Vanilla's own ground/collision fields. Vanilla {@code Entity.move}
 * writes those fields before the mod's guarded callbacks run, so movement and
 * discontinuity seams consume the same immutable snapshot rather than
 * re-deriving world-axis collision state.</p>
 */
public record VanillaCollisionState(
        boolean onGround,
        boolean horizontalCollision,
        boolean verticalCollision,
        boolean verticalCollisionBelow,
        boolean minorHorizontalCollision,
        Optional<BlockPos> mainSupportingBlockPos
) {
    public VanillaCollisionState {
        Objects.requireNonNull(
                mainSupportingBlockPos,
                "mainSupportingBlockPos"
        );

        mainSupportingBlockPos =
                mainSupportingBlockPos
                        .map(Objects::requireNonNull)
                        .map(BlockPos::immutable);

        /*
         * Vanilla Entity.checkSupportingBlock clears mainSupportingBlockPos while
         * airborne. Preserve that invariant even if an upstream custom result
         * accidentally tries to publish a physical-but-non-ground support block.
         */
        if (!onGround) {
            mainSupportingBlockPos =
                    Optional.empty();
        }
    }
}
