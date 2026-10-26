package cc.sighs.gravityengine.gravity.geometry;

import java.util.Objects;

/**
 * Decision evidence produced while planning operation-pose preparation.
 * It exists purely so one diagnostic line can describe the whole decision
 * without repeating any collision or support query.
 *
 * @param directFit                  geometric fit of the same-anchor candidate
 * @param directRejectReason         stable reason the direct candidate did not win
 * @param supportCandidateConstructed whether the support-preserving pose was
 *                                   actually built from a trusted resting snapshot
 * @param supportFit                 geometric fit of that support-preserving pose
 * @param supportRejectReason        stable reason the support candidate did not win
 * @param positionAuthorityAllowed   whether this operation may install a
 *                                   positional re-anchor at all
 * @param requiredCorrection         signed support-normal correction the
 *                                   support-preserving candidate requires, or
 *                                   {@code NaN} when it was never computed
 * @param maxCorrection              derived correction bound for this body
 */
public record OperationFrameDiagnostics(
        PoseFitStatus directFit,
        String directRejectReason,
        boolean supportCandidateConstructed,
        PoseFitStatus supportFit,
        String supportRejectReason,
        boolean positionAuthorityAllowed,
        double requiredCorrection,
        double maxCorrection
) {
    public OperationFrameDiagnostics {
        Objects.requireNonNull(directFit, "directFit");
        Objects.requireNonNull(supportFit, "supportFit");
    }
}
