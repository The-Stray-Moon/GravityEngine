package cc.sighs.gravityengine.gravity.acceleration;

import cc.sighs.gravityengine.gravity.model.CommittedGravityApplication;
import cc.sighs.gravityengine.gravity.model.GravityAccelerationMode;
import java.util.Objects;

/**
 * Complete immutable physical-evaluation context for one gravity snapshot.
 *
 * <p>Authority answers who owns gravity. This value additionally binds the
 * snapshot to the exact committed application that supplied acceleration
 * operands, plan semantics, frame fallback and field-registry dependency.</p>
 */
public record GravityEvaluationContext(
        GravityAuthorityState authority,
        long applicationEpoch,
        CommittedGravityApplication committedApplication,
        long fieldRegistryRevision
) {
    public GravityEvaluationContext {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(committedApplication, "committedApplication");
        if (applicationEpoch < 0L) {
            throw new IllegalArgumentException(
                    "applicationEpoch must be non-negative: "
                            + applicationEpoch
            );
        }
        if (fieldRegistryRevision < 0L) {
            throw new IllegalArgumentException(
                    "fieldRegistryRevision must be non-negative: "
                            + fieldRegistryRevision
            );
        }
        if (committedApplication.plan().accelerationMode()
                != GravityAccelerationMode.FIELD
                && fieldRegistryRevision
                != GravityEvaluationSnapshot.NO_FIELD_REGISTRY_REVISION) {
            throw new IllegalArgumentException(
                    "only FIELD application carries a field-registry revision"
            );
        }
    }

    public boolean usesFieldRegistry() {
        return committedApplication.plan().accelerationMode()
                == GravityAccelerationMode.FIELD;
    }

    /**
     * Exact physical-context equality. Field-registry revision participates
     * only while the committed application actually reads the field registry.
     */
    public boolean samePhysicalContext(
            GravityEvaluationContext other
    ) {
        return other != null
                && authority.sameBinding(other.authority)
                && applicationEpoch == other.applicationEpoch
                && committedApplication.equals(other.committedApplication)
                && (!usesFieldRegistry()
                || fieldRegistryRevision
                == other.fieldRegistryRevision);
    }
}
