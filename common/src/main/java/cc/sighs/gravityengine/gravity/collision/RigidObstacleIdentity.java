package cc.sighs.gravityengine.gravity.collision;

import java.util.Objects;

/**
 * Stable engine-owned identity of one rigid obstacle or primitive.
 *
 * <p>The identity deliberately excludes support faces and collision geometry:
 * it answers which publication source and primitive owns continuity across
 * operations. A support face adds its own feature address on top of this
 * value.</p>
 *
 * <p>The provider namespace is a stable semantic string owned by the
 * publication provider. It prevents two independent providers from colliding
 * merely because both use the same numeric source id.</p>
 *
 * <p>The provider registration epoch is engine-owned and allocated by
 * {@code RigidCollisionPublicationRegistry}. It distinguishes two provider
 * instances that publish under the same namespace: replacing a provider under
 * the same id advances the epoch, so persistent support captured from the old
 * instance cannot resolve against the replacement even when the namespace,
 * source id, primitive id and continuity epoch are all reused.</p>
 */
public record RigidObstacleIdentity(
        String providerNamespace,
        long providerRegistrationEpoch,
        long sourceId,
        long primitiveId,
        long continuityEpoch
) {
    /**
     * Namespace used by publications owned directly by the loader/version
     * target rather than a replaceable external provider.
     */
    public static final String NATIVE_PROVIDER_NAMESPACE =
            "gravityengine:native";

    public RigidObstacleIdentity {
        if (providerNamespace == null
                || providerNamespace.isBlank()) {
            throw new IllegalArgumentException(
                    "providerNamespace must be non-blank"
            );
        }
        if (providerRegistrationEpoch < 0L) {
            throw new IllegalArgumentException(
                    "providerRegistrationEpoch must be non-negative: "
                            + providerRegistrationEpoch
            );
        }
        if (continuityEpoch < 0L) {
            throw new IllegalArgumentException(
                    "continuityEpoch must be non-negative: "
                            + continuityEpoch
            );
        }
    }

    public static RigidObstacleIdentity of(
            DynamicCollisionObstacleSnapshot publication
    ) {
        Objects.requireNonNull(publication, "publication");
        return new RigidObstacleIdentity(
                publication.providerNamespace(),
                publication.providerRegistrationEpoch(),
                publication.sourceId(),
                publication.primitiveId(),
                publication.motion().continuityEpoch()
        );
    }

    public static RigidObstacleIdentity of(
            SupportFaceIdentity identity
    ) {
        Objects.requireNonNull(identity, "identity");
        if (identity.staticBlockSupport()) {
            throw new IllegalArgumentException(
                    "static block support has no rigid obstacle identity"
            );
        }
        return new RigidObstacleIdentity(
                identity.providerNamespace(),
                identity.providerRegistrationEpoch(),
                identity.sourceId(),
                identity.primitiveId(),
                identity.continuityEpoch()
        );
    }

    /** Exact physics identity; provider-local defaults are never wildcards. */
    public boolean matches(DynamicCollisionObstacleSnapshot publication) {
        return matchesProviderComponents(publication)
                && providerNamespace.equals(publication.providerNamespace())
                && providerRegistrationEpoch == publication.providerRegistrationEpoch();
    }

    public RigidSourceKey sourceKey() {
        return new RigidSourceKey(providerNamespace, providerRegistrationEpoch, sourceId);
    }

    /**
     * Provider-local source/primitive/continuity comparison. Only a provider
     * already selected by the registry may use this to locate its raw
     * publication. The registry qualifies the result before physics consumes it.
     */
    public boolean matchesProviderComponents(
            DynamicCollisionObstacleSnapshot publication
    ) {
        return publication != null
                && sourceId == publication.sourceId()
                && primitiveId == publication.primitiveId()
                && continuityEpoch
                == publication.motion().continuityEpoch();
    }
}
