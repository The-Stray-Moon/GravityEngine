package cc.sighs.gravityengine.gravity.kinematic;

import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Wrapper-entry movement evidence.
 *
 * <p>This is deliberately not the final collision request. The final
 * immutable request is produced only when Entity.collide receives the
 * post-vanilla-preprocessing vector.</p>
 */
public record MovementEvidence(
        Vec3 wrapperMovement,
        OwnedMotion known,
        KinematicMoveRequest.Channel channel
) {
    public MovementEvidence {
        OwnedMotion.requireFinite(wrapperMovement);
        Objects.requireNonNull(known, "known");
        Objects.requireNonNull(channel, "channel");

    }

    /**
     * Pure channel-level wrapper classification.
     *
     * <p>Minecraft MoverType is intentionally absent here. The Minecraft
     * adapter converts MoverType -> Channel before entering this layer.</p>
     */
    public static MovementEvidence capture(
            KinematicMoveRequest.Channel channel,
            Vec3 wrapper,
            OwnedMotion evidence
    ) {
        Objects.requireNonNull(channel, "channel");
        OwnedMotion.requireFinite(wrapper);
        Objects.requireNonNull(evidence, "evidence");

        OwnedMotion owners = switch (channel) {
            case SELF -> evidence;

            case PACKET_RECONCILIATION ->
                    explainPacket(wrapper, evidence);

            case PISTON, SHULKER, SHULKER_BOX ->
                    new OwnedMotion(
                            Vec3.ZERO,
                            wrapper,
                            Vec3.ZERO,
                            Vec3.ZERO
                    );

            case SUPPORT_TRANSPORT ->
                    new OwnedMotion(
                            Vec3.ZERO,
                            Vec3.ZERO,
                            Vec3.ZERO,
                            wrapper
                    );
        };

        return new MovementEvidence(
                wrapper,
                owners,
                channel
        );
    }

    public KinematicMoveRequest reconcile(Vec3 actual) {
        OwnedMotion.requireFinite(actual);

        /*
         * These channels are explicitly environment/external owned. Vanilla
         * may clamp/reduce the wrapper request before Entity.collide, but the
         * surviving vector does not change owner.
         */
        if (channel == KinematicMoveRequest.Channel.SUPPORT_TRANSPORT) {
            return new KinematicMoveRequest(
                    actual,
                    new OwnedMotion(
                            Vec3.ZERO,
                            Vec3.ZERO,
                            Vec3.ZERO,
                            actual
                    ),
                    channel
            );
        }

        if (channel == KinematicMoveRequest.Channel.PISTON
                || channel == KinematicMoveRequest.Channel.SHULKER
                || channel == KinematicMoveRequest.Channel.SHULKER_BOX) {
            return new KinematicMoveRequest(
                    actual,
                    new OwnedMotion(
                            Vec3.ZERO,
                            actual,
                            Vec3.ZERO,
                            Vec3.ZERO
                    ),
                    channel
            );
        }

        Vec3 factors = reduction(wrapperMovement, actual);
        OwnedMotion reduced =
                known.multiply(factors);

        return new KinematicMoveRequest(
                actual,
                reduced,
                channel
        );
    }

    /**
     * Detect a common uniform/component reduction without upgrading
     * unexplained motion to an explicit owner.
     */
    static Vec3 reduction(Vec3 before, Vec3 after) {
        double epsilon =
                KinematicMoveRequest.RECONCILIATION_EPSILON;

        if (before.distanceToSqr(after)
                <= KinematicMoveRequest.RECONCILIATION_EPSILON_SQUARED) {
            return new Vec3(1.0D, 1.0D, 1.0D);
        }

        double largest = Math.max(
                Math.abs(before.x),
                Math.max(
                        Math.abs(before.y),
                        Math.abs(before.z)
                )
        );

        if (largest > epsilon) {
            double scale;
            if (largest == Math.abs(before.x)) {
                scale = after.x / before.x;
            } else if (largest == Math.abs(before.y)) {
                scale = after.y / before.y;
            } else {
                scale = after.z / before.z;
            }

            if (scale >= 0.0D
                    && scale <= 1.0D
                    && before.scale(scale).distanceToSqr(after)
                    <= KinematicMoveRequest
                    .RECONCILIATION_EPSILON_SQUARED) {
                return new Vec3(scale, scale, scale);
            }
        }

        return new Vec3(
                axisReduction(before.x, after.x),
                axisReduction(before.y, after.y),
                axisReduction(before.z, after.z)
        );
    }

    private static double axisReduction(
            double before,
            double after
    ) {
        double epsilon =
                KinematicMoveRequest.RECONCILIATION_EPSILON;

        if (Math.abs(before - after) <= epsilon) {
            return 1.0D;
        }
        if (Math.abs(before) <= epsilon) {
            return 0.0D;
        }

        double scale = after / before;
        return scale >= 0.0D && scale <= 1.0D
                ? scale
                : 0.0D;
    }

    /**
     * Packet displacement consumes only the part explained by current
     * self/external evidence. Unexplained packet movement has no provenance.
     */
    public static OwnedMotion explainPacket(
            Vec3 requested,
            OwnedMotion evidence
    ) {
        Vec3 external = portion(
                requested,
                evidence.externalPush()
        );

        Vec3 self = portion(
                requested.subtract(external),
                evidence.selfWalk()
        );

        return new OwnedMotion(
                self,
                external,
                Vec3.ZERO
        );
    }

    private static Vec3 portion(
            Vec3 request,
            Vec3 evidence
    ) {
        return new Vec3(
                portion(request.x, evidence.x),
                portion(request.y, evidence.y),
                portion(request.z, evidence.z)
        );
    }

    private static double portion(
            double request,
            double evidence
    ) {
        return request * evidence > 0.0D
                ? Math.copySign(
                Math.min(
                        Math.abs(request),
                        Math.abs(evidence)
                ),
                request
        )
                : 0.0D;
    }
}
