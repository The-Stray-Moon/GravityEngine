package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.field.GravityFieldRuntime;
import cc.sighs.gravityengine.gravity.field.GravityFieldService;
import cc.sighs.gravityengine.gravity.integration.compat.sable.SableMovementCompatibility;
import cc.sighs.gravityengine.gravity.model.GravitySample;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * FallingBlock Block -> Entity start policy.
 *
 * <p>The returned support operand is consumed by Vanilla's own
 * FallingBlock.isFree(...) predicate.
 */
public final class FallingBlockStartIntegration {
    private FallingBlockStartIntegration() {}

    /**
     * Six-axis FallingBlock discretization.
     *
     * <p>A cancellation remainder below GravityFrame's direction-attach
     * threshold does not own a discrete direction. This prevents tiny
     * floating-point residuals from alternating EAST/WEST or UP/DOWN between
     * evaluations.
     *
     * <p>Ties remain deterministic: Y, then X, then Z.
     */
    public static Direction dominantDown(Vec3d acceleration) {
        if (!acceleration.isFinite()) {
            throw new IllegalArgumentException(
                    "non-finite acceleration"
            );
        }

        double strength = acceleration.length();

        if (!Double.isFinite(strength)) {
            throw new IllegalArgumentException(
                    "non-finite acceleration magnitude"
            );
        }

        if (strength < GravityFrame.DIRECTION_ATTACH_STRENGTH) {
            return null;
        }

        double x = Math.abs(acceleration.x());
        double y = Math.abs(acceleration.y());
        double z = Math.abs(acceleration.z());

        if (y >= x && y >= z) {
            return acceleration.y() < 0.0D
                    ? Direction.DOWN
                    : Direction.UP;
        }

        if (x >= z) {
            return acceleration.x() < 0.0D
                    ? Direction.WEST
                    : Direction.EAST;
        }

        return acceleration.z() < 0.0D
                ? Direction.NORTH
                : Direction.SOUTH;
    }

    public static cc.sighs.gravityengine.gravity.acceleration.GravityFieldEvaluation sample(
            ServerLevel level, BlockPos source) {
        return GravityFieldRuntime.get(level).evaluate(new cc.sighs.gravityengine.api.field.GravityFieldQuery(
                FallingBlockSampling.blockCenter(source), Vec3d.ZERO, level.getGameTime(), 1.0D));
    }

    public static BlockState supportState(
            ServerLevel level,
            BlockPos source
    ) {
        return supportState(
                level,
                source,
                true
        );
    }

    /**
     * Exact Block -> Entity predicate, reused by Entity -> Block commit.
     *
     * <p>If this returns true for a prospective installation cell, committing
     * a BlockState there would immediately schedule another FallingBlock
     * transition. Such a conversion is therefore not closed and must not
     * commit a transient block.
     */
    public static boolean wouldStartFalling(
            ServerLevel level,
            BlockPos source
    ) {
        if (source.getY() < level.getMinBuildHeight()) {
            return false;
        }

        BlockState support =
                SableMovementCompatibility
                        .isPlotPosition(level, source)
                        ? level.getBlockState(source.below())
                        : supportState(
                        level,
                        source,
                        false
                );

        return FallingBlock.isFree(support);
    }

    private static BlockState supportState(
            ServerLevel level,
            BlockPos source,
            boolean trackAffected
    ) {
        var runtime =
                GravityFieldRuntime.getIfPresent(level);

        if (runtime == null) {
            return level.getBlockState(
                    source.below()
            );
        }

        var evaluation = sample(level, source);
        // Incomplete source discovery cannot authorize block conversion.
        if (evaluation.coverage() == cc.sighs.gravityengine.api.field.FieldCoverage.INCOMPLETE) {
            return Blocks.STONE.defaultBlockState();
        }
        GravitySample sample = evaluation.sample();

        if (!sample.contributions().isEmpty()
                && trackAffected) {
            runtime.fallingBlocks()
                    .affected(level, source);
        }

        /*
         * No field contribution means exact Vanilla ownership.
         */
        if (sample.contributions().isEmpty()) {
            return level.getBlockState(
                    source.below()
            );
        }

        Direction down =
                dominantDown(
                        sample.accelerationVector()
                );

        /*
         * Near-zero/cancelled custom gravity has no trustworthy six-axis
         * direction. Suppress FallingBlock conversion instead of allowing
         * numerical noise to choose an axis.
         */
        if (down == null) {
            return Blocks.STONE.defaultBlockState();
        }

        BlockPos neighbor =
                source.relative(down);

        if (!level.getWorldBorder()
                .isWithinBounds(neighbor)
                || SableMovementCompatibility
                .isPlotPosition(level, neighbor)
                || level.getChunkSource()
                .getChunkNow(
                        neighbor.getX() >> 4,
                        neighbor.getZ() >> 4
                ) == null) {
            return Blocks.STONE.defaultBlockState();
        }

        return level.getBlockState(neighbor);
    }
}