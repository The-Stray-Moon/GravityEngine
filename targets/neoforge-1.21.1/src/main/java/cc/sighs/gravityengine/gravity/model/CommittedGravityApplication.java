package cc.sighs.gravityengine.gravity.model;

import cc.sighs.gravityengine.gravity.GravityState;
import java.util.Objects;

public record CommittedGravityApplication(
        GravityState appliedState,
        GravitySuppressionReason effectiveSuppression,
        GravityApplicationPlan plan
) {
    public CommittedGravityApplication {
        Objects.requireNonNull(appliedState, "appliedState");
        Objects.requireNonNull(effectiveSuppression, "effectiveSuppression");
        Objects.requireNonNull(plan, "plan");
    }

    public static CommittedGravityApplication vanillaNoAssignment() {
        return new CommittedGravityApplication(
                GravityState.DEFAULT,
                GravitySuppressionReason.NONE,
                GravityApplicationPlan.vanilla()
        );
    }

    public boolean usesCustomGravity() {
        return plan.usesCustomMoveSolver();
    }
}
