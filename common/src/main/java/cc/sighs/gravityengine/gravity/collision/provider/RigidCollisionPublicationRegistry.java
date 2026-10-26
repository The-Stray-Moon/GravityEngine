package cc.sighs.gravityengine.gravity.collision.provider;

import cc.sighs.gravityengine.gravity.collision.CollisionSceneBuilder;
import cc.sighs.gravityengine.gravity.collision.CollisionSceneCoverageException;
import cc.sighs.gravityengine.gravity.collision.DynamicCollisionObstacleSnapshot;
import cc.sighs.gravityengine.gravity.collision.DynamicEntityBroadphasePolicy;
import cc.sighs.gravityengine.gravity.collision.RigidPublicationCollector;
import cc.sighs.gravityengine.gravity.collision.RigidCollisionPublicationResolver;
import cc.sighs.gravityengine.gravity.collision.RigidObstacleIdentity;
import cc.sighs.gravityengine.gravity.collision.RigidSourceKey;
import cc.sighs.gravityengine.gravity.kinematic.KinematicStepContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * One loader-neutral rigid publication system for scene capture and cross-tick
 * support reacquisition.
 *
 * <p>The registry is the only owner of the source routing table. Native
 * target adapters and generic external providers both register the current
 * source ownership and can resolve the same stable
 * {@link RigidObstacleIdentity} on later ticks.</p>
 *
 * <p>The scope key is opaque. A Minecraft target normally uses its level so
 * the registry follows the existing weak/lifecycle-cleared ownership pattern;
 * tests may use an isolated object scope.</p>
 *
 * <p>The registry is also the only allocator of the engine-owned provider
 * registration epoch. Registering a different provider instance under an
 * already-used id advances that epoch, so persistent support captured from the
 * previous instance can never be routed to the replacement even when the
 * replacement reuses the same namespace, source id, primitive id and
 * continuity epoch.</p>
 */
public final class RigidCollisionPublicationRegistry {
    private static final Map<Object, ScopePublications> SCOPES =
            new WeakHashMap<>();

    private static long nextRegistrationEpoch = 1L;

    private RigidCollisionPublicationRegistry() {}

    /**
     * Registers or replaces one external provider for one scope.
     *
     * <p>Replacing a provider instance under the same id invalidates the old
     * source-routing entries. They will be claimed again by the replacement's
     * next capture. This prevents stale persistent support from being routed to
     * a logically new provider merely because it reused the same id.</p>
     *
     * @return the provider previously registered under the same id, or
     *         {@code null}
     */
    public static ExternalRigidCollisionProvider register(
            Object scope,
            ExternalRigidCollisionProvider provider
    ) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(provider, "provider");
        String id = requireId(provider);

        synchronized (SCOPES) {
            ScopePublications scoped = scope(scope);
            ProviderRegistration previous =
                    scoped.providers.get(id);
            if (previous != null
                    && previous.provider() == provider) {
                /*
                 * Re-registering the exact same instance is idempotent: the
                 * physical continuity of this provider is unchanged.
                 */
                return previous.provider();
            }
            long epoch;
            if (previous == null) {
                epoch = scoped.nextProviderRegistrationEpoch();
            } else {
                removeProviderSources(scoped, id);
                epoch = scoped.nextProviderRegistrationEpoch();
            }
            scoped.providers.put(
                    id,
                    new ProviderRegistration(provider, epoch)
            );
            return previous == null ? null : previous.provider();
        }
    }

    /**
     * Removes one provider id from one scope and all source routes it owned.
     *
     * @return whether a provider was removed
     */
    public static boolean unregister(
            Object scope,
            String providerId
    ) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(providerId, "providerId");

        synchronized (SCOPES) {
            ScopePublications scoped = SCOPES.get(scope);
            if (scoped == null) {
                return false;
            }
            boolean removed =
                    scoped.providers.remove(providerId) != null;
            if (removed) {
                removeProviderSources(scoped, providerId);
            }
            removeEmpty(scope, scoped);
            return removed;
        }
    }

    /** Clears every provider and source route registered for one scope. */
    public static void clear(Object scope) {
        Objects.requireNonNull(scope, "scope");
        synchronized (SCOPES) {
            SCOPES.remove(scope);
        }
    }

    /**
     * Immutable snapshot of one scope's live external providers in stable id
     * order.
     */
    public static List<ExternalRigidCollisionProvider> providers(
            Object scope
    ) {
        Objects.requireNonNull(scope, "scope");
        synchronized (SCOPES) {
            ScopePublications scoped = SCOPES.get(scope);
            if (scoped == null) {
                return List.of();
            }
            pruneDeadProviders(scoped);
            if (scoped.providers.isEmpty()) {
                removeEmpty(scope, scoped);
                return List.of();
            }
            List<ExternalRigidCollisionProvider> snapshot =
                    new ArrayList<>();
            for (ProviderRegistration registration
                    : scoped.providers.values()) {
                snapshot.add(registration.provider());
            }
            snapshot.sort(
                    Comparator.comparing(
                            ExternalRigidCollisionProvider::id
                    )
            );
            return List.copyOf(snapshot);
        }
    }

    /** Unknown providers remain relevant until they prove this complete query empty. */
    public static boolean mayAffect(Object scope, ExternalRigidCollisionQuery query) {
        Objects.requireNonNull(query, "query");
        for (var provider : providers(scope)) {
            try { if (provider.mayAffect(query)) return true; }
            catch (RuntimeException failure) {
                failure.addSuppressed(new IllegalStateException("external rigid provider relevance: " + provider.id()));
                throw failure;
            }
        }
        return false;
    }

    /**
     * Invokes every external provider registered for {@code scope} in stable
     * order and records source ownership for every accepted publication.
     *
     * <p>Publications are streamed directly through the registry. The registry
     * does not accumulate a provider-local temporary scene before forwarding the
     * results. This is required so bounded callers can stop a pathological
     * provider at the first publication exceeding their work budget.</p>
     */
    public static void capture(
            Object scope,
            ExternalRigidCollisionQuery query,
            RigidPublicationCollector output
    ) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(output, "output");

        pruneDeadSources(scope, query.time());

        for (ExternalRigidCollisionProvider provider
                : providers(scope)) {
            ProviderRegistration registration =
                    registrationFor(scope, provider.id());

            if (registration == null
                    || registration.provider() != provider) {
                /*
                 * The provider was replaced between the stable provider snapshot
                 * and this invocation. Do not let the retired instance publish
                 * under the replacement registration.
                 */
                continue;
            }

            /*
             * Normalize every provider-local publication before it reaches the
             * operation-owned sink.
             *
             * Important ordering:
             *
             *   validate
             *      ↓
             *   downstream addObstacle
             *      ↓
             *   claim source route
             *
             * A bounded downstream collector may throw while accepting the
             * publication. In that case this publication never becomes part of
             * the operation snapshot and must not create a source route.
             */
            RigidPublicationCollector normalizedOutput =
                    rawPublication -> {
                        if (rawPublication == null) {
                            throw new IllegalArgumentException(
                                    "external rigid provider published "
                                            + "null obstacle"
                            );
                        }

                        DynamicCollisionObstacleSnapshot publication =
                                rawPublication.withProviderIdentity(
                                        provider.id(),
                                        registration.epoch()
                                );

                        DynamicEntityBroadphasePolicy
                                .validateProviderPublication(
                                        publication,
                                        query,
                                        query.time()
                                );

                        /*
                         * This is deliberately before claimExternalSource().
                         *
                         * CollisionComplexityLimitException may escape from a
                         * bounded packet collector here. When that happens the
                         * provider capture stops immediately instead of filling
                         * an intermediate list first.
                         */
                        output.addObstacle(publication);

                        /*
                         * Only publications actually accepted by the operation
                         * may install a persistent source route.
                         */
                        claimExternalSource(
                                scope,
                                publication,
                                registration
                        );
                    };

            try {
                if (!provider.mayAffect(query)) continue;
                provider.capture(
                        query,
                        normalizedOutput
                );
            } catch (RuntimeException failure) {
                failure.addSuppressed(
                        new IllegalStateException(
                                "external rigid collision provider: "
                                        + provider.id()
                        )
                );
                throw failure;
            }
        }
    }

    /**
     * Builds a fresh operation-time builder and invokes every provider for the
     * scope. This convenience form is useful for non-Minecraft callers.
     */
    public static CollisionSceneBuilder capture(
            Object scope,
            ExternalRigidCollisionQuery query
    ) {
        Objects.requireNonNull(query, "query");
        CollisionSceneBuilder builder =
                new CollisionSceneBuilder(query.time());
        capture(scope, query, builder);
        return builder;
    }

    /**
     * Records source ownership for a target-native publication.
     *
     * <p>The resolver is retained by the registry for this scope. Native
     * adapters should therefore keep only weak references to platform
     * objects inside the resolver.</p>
     *
     * <p>A target may submit a fresh resolver wrapper on every capture. The
     * resolver's {@link RigidCollisionPublicationResolver#sameOwner} contract
     * decides whether that is a refresh of the same logical source or a
     * conflicting live owner.</p>
     */
    public static void recordPublication(
            Object scope,
            DynamicCollisionObstacleSnapshot publication,
            RigidCollisionPublicationResolver resolver
    ) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(publication, "publication");
        Objects.requireNonNull(resolver, "resolver");

        synchronized (SCOPES) {
            ScopePublications scoped = scope(scope);
            claimSource(
                    scoped,
                    RigidSourceKey.of(publication),
                    new SourceEntry(null, resolver)
            );
        }
    }

    /**
     * Identity reacquisition. No spatial search is performed; the registry
     * routes the qualified source key to its owning source and validates the
     * returned publication against the same identity/continuity epoch.
     */
    public static Optional<DynamicCollisionObstacleSnapshot> resolve(
            Object scope,
            RigidObstacleIdentity identity,
            KinematicStepContext time
    ) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(time, "time");

        RigidSourceKey sourceKey = identity.sourceKey();
        SourceEntry entry;
        ExternalRigidCollisionProvider external = null;
        long externalEpoch = 0L;

        synchronized (SCOPES) {
            ScopePublications scoped = SCOPES.get(scope);
            if (scoped == null) {
                return Optional.empty();
            }
            pruneDeadProviders(scoped);
            entry = scoped.sources.get(sourceKey);
            if (entry == null) {
                removeEmpty(scope, scoped);
                return Optional.empty();
            }
            if (entry.providerId() != null) {
                ProviderRegistration registration =
                        scoped.providers.get(entry.providerId());
                external = registration == null
                        ? null
                        : registration.provider();
                externalEpoch = registration == null
                        ? 0L
                        : registration.epoch();
                if (external == null
                        || !external.isLive()
                        || !external.isSourceLive(
                                identity.sourceId(),
                                time
                        )) {
                    scoped.sources.remove(sourceKey);
                    removeEmpty(scope, scoped);
                    return Optional.empty();
                }
            } else if (!entry.resolver().isLive()) {
                scoped.sources.remove(sourceKey);
                removeEmpty(scope, scoped);
                return Optional.empty();
            }
        }

        ExternalRigidCollisionProvider resolvedExternal = external;
        long resolvedExternalEpoch = externalEpoch;
        RigidCollisionPublicationResolver resolver =
                resolvedExternal != null
                        ? resolvedExternal
                        : entry.resolver();
        Optional<DynamicCollisionObstacleSnapshot> resolved =
                resolver.resolve(identity, time);
        Optional<DynamicCollisionObstacleSnapshot> normalized =
                resolved.map(publication -> resolvedExternal == null
                        ? publication
                        : publication.withProviderIdentity(
                                identity.providerNamespace(),
                                resolvedExternalEpoch
                        )
                );
        if (normalized.isEmpty()
                || !identity.matches(normalized.get())) {
            pruneFailedResolution(
                    scope,
                    sourceKey,
                    entry,
                    time
            );
            return Optional.empty();
        }

        try {
            DynamicEntityBroadphasePolicy.validateCaptured(
                    List.of(normalized.get()),
                    time
            );
            return normalized;
        } catch (CollisionSceneCoverageException invalidPublication) {
            /*
             * Reacquisition is fail-closed. A provider that returns a stale or
             * otherwise invalid publication must clear persistent support, not
             * abort the movement operation with a stale publication.
             */
            pruneFailedResolution(
                    scope,
                    sourceKey,
                    entry,
                    time
            );
            return Optional.empty();
        }
    }

    private static void claimExternalSource(
            Object scope,
            DynamicCollisionObstacleSnapshot publication,
            ProviderRegistration registration
    ) {
        synchronized (SCOPES) {
            ScopePublications scoped = scope(scope);
            claimSource(
                    scoped,
                    RigidSourceKey.of(publication),
                    new SourceEntry(registration.provider().id(), null)
            );
        }
    }

    private static void pruneDeadSources(
            Object scope,
            KinematicStepContext time
    ) {
        synchronized (SCOPES) {
            ScopePublications scoped = SCOPES.get(scope);
            if (scoped == null) {
                return;
            }
            pruneDeadProviders(scoped);
            scoped.sources.entrySet().removeIf(
                    entry -> !entry.getValue().isLive(
                            scoped,
                            entry.getKey().sourceId(),
                            time
                    )
            );
            removeEmpty(scope, scoped);
        }
    }

    /**
     * Removes a route after a failed primitive reacquisition only when the
     * route's source owner itself is no longer live. A missing primitive or a
     * changed continuity epoch does not imply that every other primitive under
     * the same source id disappeared.
     */
    private static void pruneFailedResolution(
            Object scope,
            RigidSourceKey sourceKey,
            SourceEntry attempted,
            KinematicStepContext time
    ) {
        synchronized (SCOPES) {
            ScopePublications scoped = SCOPES.get(scope);
            if (scoped == null) {
                return;
            }
            SourceEntry current = scoped.sources.get(sourceKey);
            if (current == null
                    || !sameOwner(current, attempted)) {
                return;
            }
            if (!current.isLive(
                    scoped,
                    sourceKey.sourceId(),
                    time
            )) {
                scoped.sources.remove(sourceKey);
                removeEmpty(scope, scoped);
            }
        }
    }

    private static void pruneDeadProviders(
            ScopePublications scoped
    ) {
        Iterator<Map.Entry<String, ProviderRegistration>> iterator =
                scoped.providers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ProviderRegistration> entry =
                    iterator.next();
            if (entry.getValue().provider().isLive()) {
                continue;
            }
            String providerId = entry.getKey();
            iterator.remove();
            removeProviderSources(scoped, providerId);
        }
    }

    private static void removeProviderSources(
            ScopePublications scoped,
            String providerId
    ) {
        scoped.sources.entrySet().removeIf(
                entry -> providerId.equals(
                        entry.getValue().providerId()
                )
        );
    }

    private static void claimSource(
            ScopePublications scoped,
            RigidSourceKey sourceKey,
            SourceEntry incoming
    ) {
        SourceEntry existing = scoped.sources.get(sourceKey);
        if (existing == null
                || sameOwner(existing, incoming)
                || !existing.isLiveWithoutTime(scoped)) {
            scoped.sources.put(sourceKey, incoming);
            return;
        }
        throw new CollisionSceneCoverageException(
                "duplicate rigid publication source identity: "
                        + sourceKey
        );
    }

    private static boolean sameOwner(
            SourceEntry first,
            SourceEntry second
    ) {
        if (first.providerId() != null
                || second.providerId() != null) {
            return Objects.equals(
                    first.providerId(),
                    second.providerId()
            );
        }
        return first.resolver().sameOwner(second.resolver())
                && second.resolver().sameOwner(first.resolver());
    }

    private static ScopePublications scope(Object scope) {
        return SCOPES.computeIfAbsent(
                scope,
                ignored -> new ScopePublications()
        );
    }

    private static ProviderRegistration registrationFor(
            Object scope,
            String providerId
    ) {
        synchronized (SCOPES) {
            ScopePublications scoped = SCOPES.get(scope);
            return scoped == null
                    ? null
                    : scoped.providers.get(providerId);
        }
    }

    /** No per-scope history is needed: registration epochs are process-wide and never reused. */
    private static void removeEmpty(Object scope, ScopePublications scoped) {
        if (scoped.providers.isEmpty() && scoped.sources.isEmpty()) SCOPES.remove(scope);
    }

    private static String requireId(
            ExternalRigidCollisionProvider provider
    ) {
        String id = provider.id();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "external rigid collision provider id must be non-blank"
            );
        }
        if (RigidObstacleIdentity.NATIVE_PROVIDER_NAMESPACE
                .equals(id)) {
            throw new IllegalArgumentException(
                    "external rigid collision provider id '"
                            + RigidObstacleIdentity
                            .NATIVE_PROVIDER_NAMESPACE
                            + "' is reserved for target-native publications"
            );
        }
        return id;
    }

    private static final class ScopePublications {
        private final Map<String, ProviderRegistration> providers =
                new LinkedHashMap<>();
        private final Map<RigidSourceKey, SourceEntry> sources =
                new HashMap<>();


        /**
         * Allocates the next engine-owned provider registration epoch.
         *
         * <p>Zero is reserved for provider-local publications, so the first
         * registered instance receives epoch 1. Exhaustion fails closed with
         * an engine coverage error instead of wrapping to a reused epoch.</p>
         */
        private long nextProviderRegistrationEpoch() {
            if (nextRegistrationEpoch
                    == Long.MAX_VALUE) {
                throw new CollisionSceneCoverageException(
                        "provider registration epoch exhausted"
                );
            }
            return nextRegistrationEpoch++;
        }
    }

    /**
     * One registered provider instance plus the engine-owned registration
     * epoch allocated when it claimed its id.
     */
    private record ProviderRegistration(
            ExternalRigidCollisionProvider provider,
            long epoch
    ) {}

    private record SourceEntry(
            String providerId,
            RigidCollisionPublicationResolver resolver
    ) {
        private boolean isLiveWithoutTime(
                ScopePublications scoped
        ) {
            if (providerId != null) {
                ProviderRegistration registration =
                        scoped.providers.get(providerId);
                return registration != null
                        && registration.provider().isLive();
            }
            return resolver.isLive();
        }

        private boolean isLive(
                ScopePublications scoped,
                long sourceId,
                KinematicStepContext time
        ) {
            if (providerId != null) {
                ProviderRegistration registration =
                        scoped.providers.get(providerId);
                return registration != null
                        && registration.provider().isLive()
                        && registration.provider()
                        .isSourceLive(sourceId, time);
            }
            return resolver.isLive();
        }
    }
}
