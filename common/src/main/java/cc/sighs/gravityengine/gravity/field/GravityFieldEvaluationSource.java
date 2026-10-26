package cc.sighs.gravityengine.gravity.field;

import cc.sighs.gravityengine.api.field.GravityFieldQuery;
import cc.sighs.gravityengine.gravity.acceleration.GravityFieldEvaluation;

/** Internal evaluation boundary. A target runtime aggregates its providers;
 * a standalone common registry represents a fully captured source set. */
public interface GravityFieldEvaluationSource {
    GravityFieldEvaluation evaluate(GravityFieldQuery query);
    long publicationRevision();

    /** True only when publication revision covers every source/coverage change. */
    default boolean revisionCoversEvaluation() { return true; }
}
