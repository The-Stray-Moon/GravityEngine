package cc.sighs.gravityengine.api.field;

/**
 * Immutable world-axis-aligned bounds of a finite gravity-field influence.
 *
 * <p>This is the only spatial value type the supported API exposes. It is a
 * plain six-scalar value: no collision geometry, no oriented boxes, no
 * spatial query operations. A custom {@link GravityInfluenceVolume} can
 * therefore report finite bounds without importing any GravityEngine
 * implementation package.</p>
 *
 * <p>Bounds are inclusive on every axis, matching the containment semantics
 * of {@link GravityInfluenceVolume#contains}. Every finite influence volume
 * must be fully contained by the bounds it reports.</p>
 */
public record GravityFieldBounds(
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ
) {
    public GravityFieldBounds {
        requireFinite(minX, "minX");
        requireFinite(minY, "minY");
        requireFinite(minZ, "minZ");
        requireFinite(maxX, "maxX");
        requireFinite(maxY, "maxY");
        requireFinite(maxZ, "maxZ");
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException(
                    "minimum bounds must not exceed maximum bounds: "
                            + "min=(" + minX + ", " + minY + ", " + minZ + ") "
                            + "max=(" + maxX + ", " + maxY + ", " + maxZ + ")"
            );
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    name + " must be finite: " + value
            );
        }
    }
}
