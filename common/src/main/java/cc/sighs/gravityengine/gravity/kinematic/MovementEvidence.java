package cc.sighs.gravityengine.gravity.kinematic;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/**
 * Wrapper-entry movement evidence.
 *
 * <p>This is deliberately not the final collision request. The final
 * immutable request is produced only when Entity.collide receives the
 * post-vanilla-preprocessing vector.</p>
 */
public record MovementEvidence(
        Vec3d wrapperMovement,
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
            Vec3d wrapper,
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
                            Vec3d.ZERO,
                            wrapper,
                            Vec3d.ZERO,
                            Vec3d.ZERO
                    );

            case SUPPORT_TRANSPORT ->
                    new OwnedMotion(
                            Vec3d.ZERO,
                            Vec3d.ZERO,
                            Vec3d.ZERO,
                            wrapper
                    );
        };

        return new MovementEvidence(
                wrapper,
                owners,
                channel
        );
    }

    public KinematicMoveRequest reconcile(Vec3d actual) {
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
                            Vec3d.ZERO,
                            Vec3d.ZERO,
                            Vec3d.ZERO,
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
                            Vec3d.ZERO,
                            actual,
                            Vec3d.ZERO,
                            Vec3d.ZERO
                    ),
                    channel
            );
        }

        Vec3d factors = reduction(wrapperMovement, actual);
        OwnedMotion reduced =
                known.multiply(factors);

        return new KinematicMoveRequest(
                actual,
                reduced,
                channel
        );
    }

    /**
     * Reconciles one request that already contains a separately-owned engine
     * support transport.
     *
     * <p>The wrapper evidence describes actor intent, so reconciliation is
     * performed on the request with transport removed. The transport is then
     * attached exactly once to the ownership breakdown.</p>
     */
    public KinematicMoveRequest reconcile(
            Vec3d actual,
            Vec3d supportTransport
    ) {
        Objects.requireNonNull(supportTransport, "supportTransport");
        OwnedMotion.requireFinite(supportTransport);

        KinematicMoveRequest actorRequest =
                reconcile(actual.subtract(supportTransport));

        if (channel == KinematicMoveRequest.Channel.SUPPORT_TRANSPORT) {
            return actorRequest;
        }

        return new KinematicMoveRequest(
                actual,
                actorRequest.ownership().add(
                        new OwnedMotion(
                                Vec3d.ZERO,
                                Vec3d.ZERO,
                                Vec3d.ZERO,
                                supportTransport
                        )
                ),
                channel
        );
    }

    /**
     * Detect a common uniform/component reduction without upgrading
     * unexplained motion to an explicit owner.
     */
    static Vec3d reduction(Vec3d before, Vec3d after) {
        double epsilon =
                KinematicMoveRequest.RECONCILIATION_EPSILON;

        if (before.distanceSquared(after)
                <= KinematicMoveRequest.RECONCILIATION_EPSILON_SQUARED) {
            return new Vec3d(1.0D, 1.0D, 1.0D);
        }

        double largest = Math.max(
                Math.abs(before.x()),
                Math.max(
                        Math.abs(before.y()),
                        Math.abs(before.z())
                )
        );

        if (largest > epsilon) {
            double scale;
            if (largest == Math.abs(before.x())) {
                scale = after.x() / before.x();
            } else if (largest == Math.abs(before.y())) {
                scale = after.y() / before.y();
            } else {
                scale = after.z() / before.z();
            }

            if (scale >= 0.0D
                    && scale <= 1.0D
                    && before.multiply(scale).distanceSquared(after)
                    <= KinematicMoveRequest
                    .RECONCILIATION_EPSILON_SQUARED) {
                return new Vec3d(scale, scale, scale);
            }
        }

        return new Vec3d(
                axisReduction(before.x(), after.x()),
                axisReduction(before.y(), after.y()),
                axisReduction(before.z(), after.z())
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
            Vec3d requested,
            OwnedMotion evidence
    ) {
        Vec3d external = portion(
                requested,
                evidence.externalPush()
        );

        Vec3d self = portion(
                requested.subtract(external),
                evidence.selfWalk()
        );

        return new OwnedMotion(
                self,
                external,
                Vec3d.ZERO
        );
    }

    private static Vec3d portion(
            Vec3d request,
            Vec3d evidence
    ) {
        return new Vec3d(
                portion(request.x(), evidence.x()),
                portion(request.y(), evidence.y()),
                portion(request.z(), evidence.z())
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
