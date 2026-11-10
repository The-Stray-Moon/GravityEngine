package cc.sighs.gravityengine.gravity.kinematic.geometry;

/**
 * Loader-neutral character collision dimensions captured at a target boundary.
 *
 * <p>This is the immutable form of a platform dimension value: the exact
 * axial-capsule body is built from these two scalars, never from a platform
 * {@code EntityDimensions} or {@code AABB}. Wide/short poses are still governed
 * by {@link CharacterDimensionPolicy}.</p>
 *
 * @param width  full horizontal character width
 * @param height full character height
 */
public record CharacterDimensions(double width, double height) {
    public CharacterDimensions {
        if (!Double.isFinite(width)
                || !Double.isFinite(height)
                || width < 0.0D
                || height < 0.0D) {
            throw new IllegalArgumentException(
                    "character dimensions must be finite and non-negative: "
                            + "width=" + width + ", height=" + height
            );
        }
    }
}
