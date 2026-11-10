package cc.sighs.gravityengine.gravity.collision;

/**
 * Immutable movement material evidence for one captured block position.
 *
 * <p>This is the only material surface a movement policy may consume after
 * scene capture. It deliberately holds no {@code Level}, {@code BlockState},
 * {@code Block}, {@code VoxelShape} or entity handle: every value was
 * evaluated exactly once at the capture boundary.</p>
 *
 * @param friction      vanilla {@code BlockState.getFriction(...)} value
 * @param speedFactor   vanilla {@code Block.getSpeedFactor()} value
 * @param water         the captured state is {@code Blocks.WATER}
 * @param bubbleColumn  the captured state is {@code Blocks.BUBBLE_COLUMN}
 */
public record BlockMovementMaterialSnapshot(
        float friction,
        float speedFactor,
        boolean water,
        boolean bubbleColumn
) {
    public BlockMovementMaterialSnapshot {
        if (!Float.isFinite(friction) || friction < 0.0F) {
            throw new IllegalArgumentException(
                    "friction must be finite and non-negative: " + friction);
        }
        if (!Float.isFinite(speedFactor) || speedFactor <= 0.0F) {
            throw new IllegalArgumentException(
                    "speedFactor must be finite and positive: " + speedFactor);
        }
    }

    /** Vanilla default when the scene proves there is no block material. */
    public static final BlockMovementMaterialSnapshot AIR =
            new BlockMovementMaterialSnapshot(0.6F, 1.0F, false, false);
}
