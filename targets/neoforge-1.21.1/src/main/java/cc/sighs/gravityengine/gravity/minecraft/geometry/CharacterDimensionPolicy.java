package cc.sighs.gravityengine.gravity.minecraft.geometry;

/** Explicit adapter decision: axial capsules cannot preserve wide/short dimensions.
 * No existing custom special-body policy authorizes a replacement for these poses. */
public final class CharacterDimensionPolicy {
    private CharacterDimensionPolicy() {}
    public enum Decision { CAPSULE, UNSUPPORTED_WIDE_SHORT }
    public static Decision decide(double width, double height) {
        if (!Double.isFinite(width) || !Double.isFinite(height) || width < 0 || height < 0)
            throw new IllegalArgumentException("finite nonnegative character dimensions required");
        return height >= width ? Decision.CAPSULE : Decision.UNSUPPORTED_WIDE_SHORT;
    }
    public static void requireCapsule(double width, double height) {
        if (decide(width, height) != Decision.CAPSULE)
            throw new UnsupportedOperationException("No custom special-body policy for height < width: "
                    + width + " x " + height);
    }
}
