package cc.sighs.gravityengine.gravity.collision.provider;

import cc.sighs.gravityengine.gravity.collision.DynamicCollisionObstacleSnapshot;
import cc.sighs.gravityengine.gravity.collision.RigidCollisionPublicationResolver;
import cc.sighs.gravityengine.gravity.collision.RigidObstacleIdentity;
import cc.sighs.gravityengine.gravity.collision.RigidPublicationCollector;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;

import java.util.Optional;

/**
 * Engine-owned extension point for external moving collision geometry.
 *
 * <p>A provider publishes immutable rigid-obstacle snapshots into an
 * engine-owned {@link RigidPublicationCollector}. The collector may be the
 * ordinary gameplay scene builder or a bounded collector used by a narrower
 * hot path such as packet occupancy validation.</p>
 *
 * <p>The provider must never assume that the collector is an unbounded
 * {@code CollisionSceneBuilder}. In particular, {@link
 * RigidPublicationCollector#addObstacle(DynamicCollisionObstacleSnapshot)}
 * may fail closed immediately when the owning operation exhausts its work
 * budget.</p>
 *
 * <p>The collector owns the captured snapshot lifetime. A provider must not
 * retain the collector or any mutable world handle through it after
 * {@link #capture(ExternalRigidCollisionQuery, RigidPublicationCollector)}
 * returns.</p>
 *
 * <p>Capture is spatial discovery. {@link #resolve} is identity reacquisition
 * for persistent support and must not require a broad world scan.</p>
 */
public interface ExternalRigidCollisionProvider
        extends RigidCollisionPublicationResolver {

    /**
     * Stable provider namespace.
     *
     * <p>The id participates in engine obstacle provenance, deterministic
     * provider ordering and provider-registration replacement. It must be
     * non-blank and must remain stable for the logical integration. Source IDs
     * need be unique only within this namespace; primitive IDs identify geometry
     * within that source. Reusing a source for a different physical object must
     * advance its continuity epoch. The native engine namespace is reserved.</p>
     */
    String id();

    /** Cheap conservative relevance proof for this complete swept domain and
     * time interval. False guarantees that capture would publish nothing.
     * Unknown providers return true. Must include motion, shape overhang and
     * every queried support/pose region; never infer absence from a past query.
     * Runs synchronously on the owning capture thread without mutation. */
    default boolean mayAffect(ExternalRigidCollisionQuery query) { return true; }

    /**
     * Publishes currently visible kinematic obstacles relevant to the query.
     *
     * <p>The collector owns snapshot lifetime. The provider must not retain
     * the collector or any mutable world handle through it. Capture may run
     * for any outer collision operation, including narrow bounded operations,
     * so implementations must stop publication immediately when
     * {@code output.addObstacle(...)} fails.</p>
     */
    void capture(
            ExternalRigidCollisionQuery query,
            RigidPublicationCollector output
    );

    /**
     * Resolves a previously published obstacle from stable engine identity.
     *
     * <p>Implementations should return only a publication owned by this
     * provider. Empty is a valid fail-closed answer for a missing primitive,
     * changed continuity epoch, unloaded source or unavailable provider.
     * The registry has already selected this namespace. Providers holding raw,
     * unqualified snapshots may use {@link RigidObstacleIdentity#matchesProviderComponents}
     * for local lookup; the registry qualifies and validates the returned value.</p>
     */
    @Override
    Optional<DynamicCollisionObstacleSnapshot> resolve(
            RigidObstacleIdentity identity,
            KinematicStepContext time
    );

    /**
     * Whether one previously published source still belongs to this provider.
     *
     * <p>This is a source-level lifecycle query, not a primitive/continuity
     * query. Returning {@code false} lets the engine forget the source-routing
     * entry even while the provider itself remains registered. Providers whose
     * set of source ids can shrink over time should override this method with
     * an O(1) or otherwise cheap lookup.</p>
     *
     * <p>The conservative default keeps the route alive for the lifetime of
     * the provider. It is appropriate only when source ids themselves are
     * provider-lifetime stable.</p>
     */
    default boolean isSourceLive(
            long sourceId,
            KinematicStepContext time
    ) {
        return isLive();
    }
}
