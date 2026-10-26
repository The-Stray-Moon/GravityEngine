package cc.sighs.gravityengine.gravity.collision;

/** Exact publication owner within one registry scope; numeric source IDs are provider-local. */
public record RigidSourceKey(String providerNamespace, long providerRegistrationEpoch, long sourceId) {
    public RigidSourceKey {
        if (providerNamespace == null || providerNamespace.isBlank()) {
            throw new IllegalArgumentException("providerNamespace must be non-blank");
        }
        if (providerRegistrationEpoch < 0) {
            throw new IllegalArgumentException("providerRegistrationEpoch must be non-negative");
        }
    }

    public static RigidSourceKey of(DynamicCollisionObstacleSnapshot publication) {
        return new RigidSourceKey(publication.providerNamespace(),
                publication.providerRegistrationEpoch(), publication.sourceId());
    }
}
