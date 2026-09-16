package cc.sighs.gravityengine.gravity.movement;

import cc.sighs.gravityengine.attitude.AttitudeSpaceTransform;
import cc.sighs.gravityengine.gravity.GravityFrame;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import java.util.Objects;

/** Canonical left/up/forward control axes, distinct from physical body ownership. */
public record CharacterMovementBasis(Vec3 left, Vec3 up, Vec3 forward) {
    public CharacterMovementBasis {
        Objects.requireNonNull(left); Objects.requireNonNull(up); Objects.requireNonNull(forward);
        new cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d(
                left.x, left.y, left.z, up.x, up.y, up.z, forward.x, forward.y, forward.z);
    }
    public static CharacterMovementBasis resolve(CharacterControlBasis kind, GravityFrame frame,
            Vec3 viewForward, Vec3 viewUp, Vec3 poleFallback, Quaterniond acceptedBody) {
        Objects.requireNonNull(kind, "kind");
        if (kind == CharacterControlBasis.BODY_3D) {
            Objects.requireNonNull(acceptedBody, "BODY_3D requires committed actor Qbody");
            return new CharacterMovementBasis(AttitudeSpaceTransform.bodyLeftWorld(acceptedBody),
                    AttitudeSpaceTransform.bodyUpWorld(acceptedBody), AttitudeSpaceTransform.bodyForwardWorld(acceptedBody));
        }
        Vec3 forward = kind == CharacterControlBasis.GRAVITY_TANGENT
                ? GravityPhysics.tangentForward(viewForward, frame, poleFallback) : viewForward.normalize();
        Vec3 up = kind == CharacterControlBasis.GRAVITY_TANGENT ? frame.up() : viewUp;
        Vec3 left = up.cross(forward).normalize();
        return new CharacterMovementBasis(left, forward.cross(left).normalize(), forward);
    }
    public Vec3 propulsion(Vec3 request, double speed) {
        Vec3 normalized = request.lengthSqr() > 1 ? request.normalize() : request;
        return left.scale(normalized.x).add(up.scale(normalized.y)).add(forward.scale(normalized.z)).scale(speed);
    }
}
