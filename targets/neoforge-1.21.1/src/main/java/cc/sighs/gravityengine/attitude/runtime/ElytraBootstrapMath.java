package cc.sighs.gravityengine.attitude.runtime;

import cc.sighs.gravityengine.math.Quatd;
import java.util.Objects;

/**
 * Pure composition for the Vanilla 1.21.1 Elytra presentation baseline.
 *
 * <p>The Minecraft snapshot adapter owns reading player state; this class owns
 * only the canonical quaternion composition.</p>
 */
public final class ElytraBootstrapMath {
    private ElytraBootstrapMath() {
    }

    public static Quatd compose(
            Quatd frameRotation,
            float yBodyRotDegrees,
            float xRotDegrees,
            float flightBlend,
            Double alignmentAngleRadians
    ) {
        Objects.requireNonNull(frameRotation, "frameRotation");

        Quatd local = Quatd.rotationY(
                Math.toRadians(180.0F - yBodyRotDegrees))
                .multiply(Quatd.rotationX(
                        Math.toRadians(
                                flightBlend
                                        * (-90.0F - xRotDegrees))));
        if (alignmentAngleRadians != null) {
            local = local.multiply(Quatd.rotationY(
                    alignmentAngleRadians));
        }
        local = local.multiply(Quatd.rotationY(-Math.PI));
        return frameRotation.multiply(local).normalized();
    }
}
