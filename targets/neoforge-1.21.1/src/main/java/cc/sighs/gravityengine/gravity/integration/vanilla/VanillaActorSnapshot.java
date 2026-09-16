package cc.sighs.gravityengine.gravity.integration.vanilla;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import cc.sighs.gravityengine.look.SemanticLookSnapshot;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.Objects;

/**
 * One immutable, coherent gameplay actor sample.
 *
 * <p>The sample contains exactly four independent spatial facts:
 *
 * <ul>
 *   <li>{@code referenceFrame} - the single environmental
 *       {@link GravityFrame} used by this logical bridge invocation;</li>
 *   <li>{@code attachmentFrame} - the gameplay attachment reference,
 *       independent from actor attitude and model heading;</li>
 *   <li>{@code look} - one semantic-look snapshot captured against that same
 *       frame;</li>
 *   <li>physical geometry - gravity reference feet Fg, center C, eye, width, height and nominal eye
 *       height.</li>
 * </ul>
 *
 * No live {@code Entity}, {@code Level}, supplier, callback or mutable
 * component is retained.  Snapshots are invocation-local and must never be
 * stored across ticks or inside {@code GravityRuntimeState}.
 */
public record VanillaActorSnapshot(
        GravityFrame referenceFrame,
        OrthonormalFrame3d attachmentFrame,
        SemanticLookSnapshot look,
        Vec3 feet,
        Vec3 center,
        Vec3 eye,
        double width,
        double height,
        double eyeHeight,
        boolean customBody
) {
    /** Vanilla entity/network anchor P; feet() is the gravity reference Fg. */
    public Vec3 positionAnchor() {
        return cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry
                .positionAnchorFromBodyCenter(center, height);
    }

    public VanillaActorSnapshot {
        Objects.requireNonNull(referenceFrame, "referenceFrame");
        Objects.requireNonNull(attachmentFrame, "attachmentFrame");
        Objects.requireNonNull(look, "look");
        Objects.requireNonNull(feet, "feet");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(eye, "eye");
        requireFinite(feet, "feet");
        requireFinite(center, "center");
        requireFinite(eye, "eye");
        requireFinite(width, "width");
        requireFinite(height, "height");
        requireFinite(eyeHeight, "eyeHeight");
        if (width < 0.0D || height < 0.0D || eyeHeight < 0.0D) {
            throw new IllegalArgumentException(
                    "width/height/eyeHeight must be non-negative");
        }
    }

    /** Environmental reference up ({@code GravityFrame.up()}). */
    public Vec3 referenceUp() {
        return referenceFrame.up();
    }

    /** Environmental reference down ({@code GravityFrame.down()}). */
    public Vec3 referenceDown() {
        return referenceFrame.down();
    }

    /** Attachment up; this is not an alias of {@link #referenceUp()}. */
    public Vec3 attachmentUp() {
        return axisToMinecraft(attachmentFrame.axisY(new Vector3d()));
    }

    /** Attachment down; opposite of {@link #attachmentUp()}. */
    public Vec3 attachmentDown() {
        return attachmentUp().reverse();
    }

    /** Attachment forward from the gameplay attachment reference. */
    public Vec3 attachmentForward() {
        return axisToMinecraft(attachmentFrame.axisZ(new Vector3d()));
    }

    /** Semantic view forward; not an alias of attachment forward or reference. */
    public Vec3 viewForward() {
        return look.forward();
    }

    /** Semantic view up; not an alias of attachment up or reference up. */
    public Vec3 viewUp() {
        return look.up();
    }

    /**
     * {@code true} when the semantic look is not the Vanilla world-axis
     * scalar stream, so view/launch operands need a reference-aware
     * translation. The owning operation decides what that means for its own
     * launch, aim, target or hit geometry.
     */
    public boolean transformedLook() {
        return look.source() != SemanticLookSnapshot.Source.VANILLA;
    }

    /**
     * {@code true} when the environmental reference frame differs from
     * world-down, so reference-relative quantities are not world-axis
     * quantities. The owning operation decides which of its operands are
     * reference-relative.
     */
    public boolean nonDefaultReferenceFrame() {
        return !referenceFrame.isDefault();
    }

    /** Zero-pitch semantic heading used by movement and world-carrier fallbacks. */
    public Vec3 zeroPitchHeading() {
        return look.zeroPitchForward();
    }

    /**
     * Exact target body for this sample.  Only call this when
     * {@link #customBody()} is true; ordinary Vanilla targets remain
     * world-axis AABB entities whose enclosing box is their exact occupancy
     * representation.
     */
    public CharacterCapsule exactBody() {
        if (!customBody) {
            throw new IllegalStateException(
                    "ordinary Vanilla actor has no independent exact body");
        }
        return cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry.characterBodyAtCenter(
                width, height, center, referenceFrame.up());
    }

    private static void requireFinite(Vec3 value, String name) {
        if (!Double.isFinite(value.x)
                || !Double.isFinite(value.y)
                || !Double.isFinite(value.z)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static Vec3 axisToMinecraft(Vector3d axis) {
        return new Vec3(axis.x, axis.y, axis.z);
    }
}
