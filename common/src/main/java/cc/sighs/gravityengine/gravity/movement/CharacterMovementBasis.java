package cc.sighs.gravityengine.gravity.movement;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.attitude.AttitudeSpaceTransform;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.math.Quatd;
import java.util.Objects;

/** Canonical left/up/forward control axes, distinct from physical body ownership. */
public record CharacterMovementBasis(Vec3d left, Vec3d up, Vec3d forward) {
    public CharacterMovementBasis {
        Objects.requireNonNull(left); Objects.requireNonNull(up); Objects.requireNonNull(forward);
        new cc.sighs.gravityengine.math.geometry.OrthonormalFrame3d(
                left.x(), left.y(), left.z(), up.x(), up.y(), up.z(), forward.x(), forward.y(), forward.z());
    }
    public static CharacterMovementBasis resolve(CharacterControlBasis kind, GravityFrame frame,
            Vec3d viewForward, Vec3d viewUp, Vec3d poleFallback, Quatd acceptedBody) {
        Objects.requireNonNull(kind, "kind");
        if (kind == CharacterControlBasis.BODY_3D) {
            Objects.requireNonNull(acceptedBody, "BODY_3D requires committed actor Qbody");
            return new CharacterMovementBasis(AttitudeSpaceTransform.bodyLeftWorld(acceptedBody),
                    AttitudeSpaceTransform.bodyUpWorld(acceptedBody), AttitudeSpaceTransform.bodyForwardWorld(acceptedBody));
        }
        Vec3d forward = kind == CharacterControlBasis.GRAVITY_TANGENT
                ? GravityPhysics.tangentForward(viewForward, frame, poleFallback) : viewForward.normalized();
        Vec3d up = kind == CharacterControlBasis.GRAVITY_TANGENT ? frame.up() : viewUp;
        Vec3d left = up.cross(forward).normalized();
        return new CharacterMovementBasis(left, forward.cross(left).normalized(), forward);
    }
    public Vec3d propulsion(Vec3d request, double speed) {
        Vec3d normalized = request.lengthSquared() > 1 ? request.normalized() : request;
        return left.multiply(normalized.x()).add(up.multiply(normalized.y())).add(forward.multiply(normalized.z())).multiply(speed);
    }
}
