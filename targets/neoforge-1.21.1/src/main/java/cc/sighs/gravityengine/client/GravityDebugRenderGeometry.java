package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** Pure eye-sensor geometry for the Vanilla F3+B overlay. The square is a
 * presentation sensor in the supplied gravity reference, independent from
 * the axisymmetric character collider. */
public final class GravityDebugRenderGeometry {

    /** Vanilla {@code EntityRenderDispatcher.renderHitbox} blue ray length. */
    public static final double VANILLA_DEBUG_RAY_LENGTH = 2.0D;

    private GravityDebugRenderGeometry() {}

    /**
     * Four corners of the eye-level square in perimeter order
     * {@code A -> B -> C -> D}. {@code a}/{@code b}/{@code c}/{@code d}
     * correspond to the spec corners:
     *
     * <pre>
     *   a = center + axisA * half + axisB * half
     *   b = center - axisA * half + axisB * half
     *   c = center - axisA * half - axisB * half
     *   d = center + axisA * half - axisB * half
     * </pre>
     */
    public record EyePlane(Vec3 center, Vec3 a, Vec3 b, Vec3 c, Vec3 d) {
        public EyePlane {
            Objects.requireNonNull(center, "center");
            Objects.requireNonNull(a, "a");
            Objects.requireNonNull(b, "b");
            Objects.requireNonNull(c, "c");
            Objects.requireNonNull(d, "d");
        }
    }

    /**
     * Builds the eye-level square around a world-space eye center.
     *
     * <p>The square spans the frame's tangent axes. Under default gravity it
     * reproduces Vanilla's F3+B eye rectangle. It is a sensor marker, not a
     * cross section of the character collision body.</p>
     */
    public static EyePlane eyePlaneAt(
            Vec3 eyeCenter,
            GravityFrame frame,
            double bodyWidth
    ) {
        Objects.requireNonNull(eyeCenter, "eyeCenter");
        Objects.requireNonNull(frame, "frame");
        double halfWidth = bodyWidth * 0.5D;
        Vec3 axisA =
                MinecraftMathAdapter.toMinecraft(
                        frame.left().negate()
                ).scale(halfWidth);
        Vec3 axisB =
                MinecraftMathAdapter.toMinecraft(
                        frame.forward()
                ).scale(halfWidth);
        return new EyePlane(
                eyeCenter,
                eyeCenter.add(axisA).add(axisB),
                eyeCenter.subtract(axisA).add(axisB),
                eyeCenter.subtract(axisA).subtract(axisB),
                eyeCenter.add(axisA).subtract(axisB)
        );
    }

}
