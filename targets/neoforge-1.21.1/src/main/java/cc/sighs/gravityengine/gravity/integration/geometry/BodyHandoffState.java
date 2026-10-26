package cc.sighs.gravityengine.gravity.integration.geometry;

import cc.sighs.gravityengine.gravity.geometry.BodyRepresentation;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.model.GravityApplicationPlan;
import cc.sighs.gravityengine.gravity.model.GravityCollisionRoute;
import cc.sighs.gravityengine.gravity.policy.GravityInfluencePolicy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Immutable snapshot of the authoritative body state one handoff decision
 * depends on.
 *
 * <p>The snapshot keeps four independent state dimensions apart, and every
 * comparison helper below compares exactly one of them:</p>
 *
 * <ul>
 *   <li>{@link ApplicationSnapshot} - how gravity and movement should be
 *       applied;</li>
 *   <li>{@link GravityCollisionRoute committedCollisionRoute} - the committed
 *       collision/movement policy owner, i.e. which path would own a new
 *       operation under the currently committed state;</li>
 *   <li>{@link InstalledBodySnapshot} - which physical/collision representation
 *       is actually installed on the entity;</li>
 *   <li>{@link BodyAnchorSnapshot} - the authoritative kinematic anchor and its
 *       world-space velocity.</li>
 * </ul>
 *
 * <p>This snapshot is a handoff/synchronization value. It never carries the
 * frozen route of a physical {@code Entity.move}: an already-running movement
 * operation consumes only
 * {@code GravityOperationState.movementCollisionRoute()}, which the outer
 * movement boundary publishes from its own execution plan. Do not feed this
 * value back into a running move.</p>
 *
 * <p>Application semantics and movement routing are not installed geometry. A
 * committed application change, an application epoch change, a routing change
 * or a receiver's resynchronization request never by itself means that the
 * collider changed, and the kinematic anchor (and therefore ordinary
 * translation) never takes part in installed-geometry identity.</p>
 */
public record BodyHandoffState(
        ApplicationSnapshot application,
        GravityCollisionRoute committedCollisionRoute,
        InstalledBodySnapshot installedBody,
        BodyAnchorSnapshot anchor
) {
    /**
     * How gravity/movement should be applied: the committed plan, its
     * publication epoch and whether the committed snapshot is installable as
     * application metadata alone.
     */
    public record ApplicationSnapshot(
            GravityApplicationPlan plan,
            long epoch,
            boolean nativeApplicationCommit
    ) {
        public ApplicationSnapshot {
            Objects.requireNonNull(plan, "plan");
            if (epoch < 0L) {
                throw new IllegalArgumentException(
                        "application epoch must be non-negative: " + epoch
                );
            }
        }
    }

    /** The authoritative world-space position anchor and its momentum. */
    public record BodyAnchorSnapshot(Vec3 positionAnchor, Vec3 velocityWorld) {
        public BodyAnchorSnapshot {
            Objects.requireNonNull(positionAnchor, "positionAnchor");
            Objects.requireNonNull(velocityWorld, "velocityWorld");
        }
    }

    public BodyHandoffState {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(
                committedCollisionRoute,
                "committedCollisionRoute"
        );
        Objects.requireNonNull(installedBody, "installedBody");
        Objects.requireNonNull(anchor, "anchor");
        if (application.nativeApplicationCommit()
                && installedBody.representation() != BodyRepresentation.NATIVE_AABB) {
            throw new IllegalArgumentException(
                    "a metadata-only commit describes a native application");
        }
    }

    public static BodyHandoffState capture(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        var component = GravityEntityAccess.cast(player)
                .gravityengine$gravityComponent();
        Vec3d installedUp = component.operationState().installedCollisionUp();
        return new BodyHandoffState(
                new ApplicationSnapshot(
                        component.state().appliedPlan(),
                        component.state().applicationEpoch(),
                        NativeAabbApplicationCommit.isNativeApplication(
                                component.state().committedApplication(),
                                installedUp
                        )
                ),
                GravityInfluencePolicy.collisionRoute(player),
                InstalledBodySnapshot.capture(player),
                new BodyAnchorSnapshot(
                        player.position(),
                        player.getDeltaMovement()
                )
        );
    }

    /** True when the committed application semantics changed. */
    public boolean applicationDiffersFrom(BodyHandoffState other) {
        Objects.requireNonNull(other, "other");
        return !application.equals(other.application);
    }

    /**
     * True when the committed movement/collision routing changed. This is an
     * ownership decision, never evidence that a collider was installed or
     * removed, and never the frozen route of an active move.
     */
    public boolean routingDiffersFrom(BodyHandoffState other) {
        Objects.requireNonNull(other, "other");
        return committedCollisionRoute != other.committedCollisionRoute;
    }

    /**
     * True when the actually installed body/collider representation changed.
     * This is the only input that may select a representation transition.
     */
    public boolean installedGeometryDiffersFrom(BodyHandoffState other) {
        Objects.requireNonNull(other, "other");
        return installedBody.differsFrom(other.installedBody);
    }

    /**
     * True when the authoritative position anchor actually moved. A relocation
     * is never inferred from a synchronization request, an application change
     * or a representation change.
     */
    public boolean positionAnchorDiffersFrom(BodyHandoffState other) {
        Objects.requireNonNull(other, "other");
        return !anchor.positionAnchor().equals(other.anchor.positionAnchor());
    }
}
