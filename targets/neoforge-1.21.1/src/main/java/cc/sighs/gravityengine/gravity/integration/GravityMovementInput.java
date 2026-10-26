package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.movement.GravityPhysics;
import cc.sighs.gravityengine.look.PlayerLookIntegration;
import cc.sighs.gravityengine.look.SemanticLookSnapshot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Converts movement input through the actor's semantic view and gravity frame.
 * Vanilla movement seams and capture validation share these spatial operands.
 */
public final class GravityMovementInput {
    private GravityMovementInput() {}

    /**
     * Calculates the relative movement vector from player input using the
     * entity's resolved semantic world view.
     *
     * <p>This Minecraft-facing boundary resolves the semantic view exactly
     * once and then delegates to the pure semantic vector API. The
     * standalone {@code Entity.moveRelative} path uses this entry. Travel
     * captures look in its invocation context and reuses its computed input
     * contribution for both coverage validation and the native helper.</p>
     */
    public static Vec3 calculateRelativeMovement(
            Entity entity,
            float speed,
            Vec3 input,
            GravityFrame frame
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(frame, "frame");
        SemanticLookSnapshot look =
                PlayerLookIntegration.capture(entity,
                        cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess.authoritativeFrame(entity));
        return MinecraftMathAdapter.toMinecraft(
                GravityPhysics.calculateRelativeMovement(
                        look.forward(),
                        speed,
                        MinecraftMathAdapter.toVec3d(input),
                        frame,
                        look.zeroPitchForward()
                )
        );
    }

    /**
     * Caller-supplied stable heading used only when the semantic view is
     * near-parallel to {@code frame.up}.  Active BodyAttitude players reuse
     * the pitch-zero semantic view heading, the gravity-local path retains
     * the pitch-zero yaw heading from the captured environmental reference, and ordinary
     * entities keep their Vanilla look.  {@code PlayerLookIntegration} is the
     * single authority.
     */
    static Vec3 resolveMovementFallbackHeading(
            Entity entity,
            GravityFrame frame
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(frame, "frame");
        return MinecraftMathAdapter.toMinecraft(
                PlayerLookIntegration.capture(entity,
                        cc.sighs.gravityengine.gravity.minecraft.GravityFrameAccess.authoritativeFrame(entity))
                        .zeroPitchForward());
    }
}
