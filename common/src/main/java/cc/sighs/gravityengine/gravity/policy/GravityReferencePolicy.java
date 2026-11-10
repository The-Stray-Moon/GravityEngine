package cc.sighs.gravityengine.gravity.policy;

import cc.sighs.gravityengine.gravity.model.GravityAuthorityMode;
import cc.sighs.gravityengine.gravity.model.GravitySuppressionReason;
import java.util.Objects;

/** Reference availability from captured assignment and presentation capability. */
public final class GravityReferencePolicy {
    private GravityReferencePolicy() {}

    /**
     * Pure gravity-reference predicate for ownership-focused tests.
     *
     * <p>{@code fieldReferenceInForce} is the reconciliation-aware reference
     * evidence, not a raw "confirmed present" boolean.</p>
     */
    public static boolean hasGravityReference(
            GravityAuthorityMode authority,
            boolean fieldReferenceInForce,
            GravitySuppressionReason suppression,
            boolean presentationCapable,
            cc.sighs.gravityengine.gravity.GravityFrame frame
    ) {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(suppression, "suppression");
        if (!presentationCapable
                || suppression != GravitySuppressionReason.NONE
                || frame == null) {
            return false;
        }
        return authority == GravityAuthorityMode.DIRECT
                || (authority == GravityAuthorityMode.FIELD
                && fieldReferenceInForce);
    }

}
