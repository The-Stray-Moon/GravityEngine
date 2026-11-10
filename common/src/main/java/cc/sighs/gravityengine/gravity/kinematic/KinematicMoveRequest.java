package cc.sighs.gravityengine.gravity.kinematic;

import cc.sighs.gravityengine.api.math.Vec3d;
import java.util.Objects;

/**
 * One actual Entity.collide request, frozen after all vanilla preprocessing.
 *
 * <p>The request is Minecraft-integration data, but its operation-local evidence semantics
 * are independent from collision-kernel tolerances. Reconciliation tolerance
 * therefore lives with provenance rather than depending on the collision
 * package.</p>
 */
public record KinematicMoveRequest(
        Vec3d actualMovement,
        OwnedMotion ownership,
        Channel channel
) {
    static final double RECONCILIATION_EPSILON = 1.0E-7D;
    static final double RECONCILIATION_EPSILON_SQUARED =
            RECONCILIATION_EPSILON * RECONCILIATION_EPSILON;

    public enum Channel {
        /** Ordinary self-driven locomotion sampled by the entity's own tick. */
        SELF,
        /**
         * The translation proposal Vanilla's packet loop submitted through the
         * single {@code player.move(MoverType.PLAYER, ...)}. Vanilla owns
         * whether that proposal is accepted or corrected; this channel only
         * classifies the physical request's provenance.
         */
        PACKET_RECONCILIATION,
        PISTON,
        SHULKER,
        SHULKER_BOX,

        /**
         * Environment-owned displacement supplied by an already-resolved
         * moving support/reference space. It is neither actor locomotion nor
         * an impulse. External compatibility transports may use this channel.
         */
        SUPPORT_TRANSPORT
    }

    public KinematicMoveRequest {
        OwnedMotion.requireFinite(actualMovement);
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(channel, "channel");

    }
}
