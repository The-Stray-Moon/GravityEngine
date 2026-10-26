package cc.sighs.gravityengine.gravity.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Objects;

/**
 * Frozen result of one completed FallingBlock movement.
 *
 * <p>Non-null context always means a real physical landing.
 *
 * <p>{@code installable} means the collision geometry identifies a static
 * Minecraft support face and therefore owns an exact placement/support
 * BlockPos pair.
 *
 * <p>{@code blockConversionAllowed} is stronger again. It is server-authored
 * after checking that converting the entity to a block would not immediately
 * satisfy the Block -> Entity FallingBlock start predicate.
 */
public record FallingBlockLandingContext(
        boolean landed,
        Direction down,
        boolean installable,
        boolean blockConversionAllowed,
        BlockPos placementPos,
        BlockPos supportPos
) {
    public FallingBlockLandingContext {
        Objects.requireNonNull(down, "down");

        if (!landed) {
            throw new IllegalArgumentException(
                    "non-null landing context must represent a landing"
            );
        }

        if (blockConversionAllowed
                && !installable) {
            throw new IllegalArgumentException(
                    "non-installable landing cannot authorize block conversion"
            );
        }

        if (installable) {
            Objects.requireNonNull(
                    placementPos,
                    "placementPos"
            );
            Objects.requireNonNull(
                    supportPos,
                    "supportPos"
            );
        } else if (placementPos != null
                || supportPos != null) {
            throw new IllegalArgumentException(
                    "non-installable landing cannot own block positions"
            );
        }
    }

    public static FallingBlockLandingContext
    nonInstallableLanding(Direction down) {
        return new FallingBlockLandingContext(
                true,
                down,
                false,
                false,
                null,
                null
        );
    }

    /**
     * Geometry-only static landing.
     *
     * <p>Conversion is deliberately false here. Only the server-side
     * post-move conversion validator may promote it to true.
     */
    public static FallingBlockLandingContext staticLanding(
            Direction down,
            BlockPos supportPos
    ) {
        Objects.requireNonNull(
                supportPos,
                "supportPos"
        );

        return new FallingBlockLandingContext(
                true,
                down,
                true,
                false,
                supportPos.relative(
                        down.getOpposite()
                ),
                supportPos
        );
    }

    public FallingBlockLandingContext
    withBlockConversionAllowed(boolean allowed) {
        if (allowed && !installable) {
            throw new IllegalArgumentException(
                    "non-installable landing cannot authorize block conversion"
            );
        }

        return new FallingBlockLandingContext(
                landed,
                down,
                installable,
                allowed,
                placementPos,
                supportPos
        );
    }
}