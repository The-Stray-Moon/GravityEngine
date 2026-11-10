package cc.sighs.gravityengine.gravity.acceleration;

import cc.sighs.gravityengine.api.field.GravityFieldQuery;
import cc.sighs.gravityengine.api.field.FieldCoverage;
import cc.sighs.gravityengine.gravity.model.GravitySample;
import java.util.Objects;

/**
 * Raw composed field evidence sampled for assignment resolution.
 *
 * <p>This is deliberately not a physical runtime snapshot: assignment and
 * application may still be deferred or rejected after the sample. The
 * physical truth is constructed later from the committed application
 * context.</p>
 */
public record GravityFieldEvaluation(
        GravityFieldQuery query,
        GravitySample sample,
        FieldCoverage coverage,
        long fieldRegistryRevision
) {
    public GravityFieldEvaluation {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(sample, "sample");
        Objects.requireNonNull(coverage, "coverage");
        if (fieldRegistryRevision < 0L) {
            throw new IllegalArgumentException(
                    "fieldRegistryRevision must be non-negative: "
                            + fieldRegistryRevision
            );
        }
        if (!sample.samplePoint().equals(query.position())) {
            throw new IllegalArgumentException(
                    "field evaluation sample point must match query position"
            );
        }
    }
}
