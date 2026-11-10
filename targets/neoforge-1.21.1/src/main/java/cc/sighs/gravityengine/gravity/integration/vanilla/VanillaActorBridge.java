package cc.sighs.gravityengine.gravity.integration.vanilla;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import cc.sighs.gravityengine.look.PlayerLookIntegration;
import cc.sighs.gravityengine.look.SemanticLookSnapshot;
import cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Captures the one coherent gameplay actor sample required by a Vanilla
 * gameplay-spatial bridge.
 *
 * <p>Every capture reads the reference frame at most once for semantic look;
 * physical attachments separately consume the installed collision axis. A caller that already
 * owns a {@code GravityFrame} must use the explicit-frame overload; the
 * one-argument convenience resolves the authoritative frame once only when
 * the caller genuinely has no operation frame.</p>
 */
public final class VanillaActorBridge {
    private VanillaActorBridge() {}

    public static VanillaActorSnapshot capture(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        return capture(entity, GravityFrameAccess.authoritativeFrame(entity));
    }

    /** Convenience overload for a seam that already has explicit scalar look inputs. */
    public static VanillaActorSnapshot capture(
            Entity entity,
            float explicitYaw,
            float explicitPitch
    ) {
        Objects.requireNonNull(entity, "entity");
        return capture(
                entity,
                GravityFrameAccess.authoritativeFrame(entity),
                explicitYaw,
                explicitPitch);
    }

    /**
     * Canonical capture.  The caller owns {@code frame}; this method never
     * samples gravity and never re-resolves the entity frame.
     */
    public static VanillaActorSnapshot capture(
            Entity entity,
            GravityFrame frame
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(frame, "frame");
        return capture(
                entity,
                frame,
                entity.getYRot(),
                entity.getXRot());
    }

    /**
     * Canonical capture with explicit scalar Vanilla scalar inputs.  Active
     * BodyAttitude look remains body-relative and ignores the scalars; other
     * sources resolve exactly the supplied scalars.
     */
    public static VanillaActorSnapshot capture(
            Entity entity,
            GravityFrame frame,
            float explicitYaw,
            float explicitPitch
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(frame, "frame");
        SemanticLookSnapshot look = PlayerLookIntegration.capture(
                entity, frame, explicitYaw, explicitPitch);

        boolean customBody = GravityInfluencePolicy.usesCustomBody(entity);
        OrthonormalFrame3d attachmentFrame;
        Vec3 center;
        Vec3 eye;
        if (customBody) {
            var installed = GravityFrame.completedOnUpAxis(GravityEntityGeometry.installedUp(entity), frame);
            attachmentFrame = installed.orientation();
            center = GravityEntityGeometry.bodyCenter(entity);
            eye = GravityEntityGeometry.eyePosition(entity, center, installed);
        } else {
            attachmentFrame = OrthonormalFrame3d.IDENTITY;
            center = entity.getBoundingBox().getCenter();
            eye = entity.getEyePosition();
        }
        return new VanillaActorSnapshot(
                frame,
                attachmentFrame,
                look,
                GravityEntityGeometry.gravityFeetFromBodyCenter(center,
                        GravityEntityGeometry.dimensions(entity).height(),
                        MinecraftMathAdapter.toMinecraft(
                                attachmentFrame.axisY().negate()
                        )),
                center,
                eye,
                entity.getBbWidth(),
                entity.getBbHeight(),
                entity.getEyeHeight(),
                customBody
        );
    }

    /**
     * Package-internal exact-body query that deliberately does not capture
     * semantic look.
     *
     * <p>Targeting, reach and sweep narrow phase need target geometry only.
     * Reading a target player's pending look merely to obtain its exact body would
     * violate the geometry/view ownership boundary.</p>
     */
    @javax.annotation.Nullable
    static cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule
    exactBodyForQuery(Entity entity) {
        Objects.requireNonNull(entity, "entity");

        if (!GravityInfluencePolicy.usesCustomBody(entity)) {
            return null;
        }

        return GravityEntityGeometry.exactBody(entity);
    }
}
