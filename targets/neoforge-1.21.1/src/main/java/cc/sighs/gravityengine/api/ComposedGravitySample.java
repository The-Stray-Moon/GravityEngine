package cc.sighs.gravityengine.api;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.model.GravitySample;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable result of one gravity-field composition with query coverage.
 *
 * <p>Only COMPLETE coverage authorizes presence or absence. An INCOMPLETE
 * result may contain partial positive contributions; consumers must not apply
 * that composition as authoritative gravity.</p>
 *
 * <p>The acceleration is the world-space vector sum of the effective
 * composition group. Ordinary
 * {@link cc.sighs.gravityengine.api.field.GravityFieldCompositionMode#ADDITIVE}
 * fields compose together; when one or more active
 * {@link cc.sighs.gravityengine.api.field.GravityFieldCompositionMode#OVERRIDE}
 * fields exist, only the OVERRIDE group participates.</p>
 *
 * <p>Contribution presence and resultant magnitude are separate facts:
 * {@link #fieldPresent()} is true whenever the effective group is non-empty,
 * even if every contribution and therefore the resultant is zero.</p>
 *
 * <p>{@link #contributions()} is unmodifiable and follows the engine's
 * deterministic accumulation order: source type, then block X/Y/Z, followed
 * by the stable publication key as the final tie-break when two fields have
 * identical source order. Registration time, revision and container
 * iteration order do not affect this ordering.</p>
 */
public record ComposedGravitySample(
        Vec3d samplePoint,
        Vec3d acceleration,
        List<GravityContributionView> contributions,
        cc.sighs.gravityengine.api.field.FieldCoverage coverage
) {
    public ComposedGravitySample {
        Objects.requireNonNull(coverage, "coverage");
        Objects.requireNonNull(samplePoint, "samplePoint");
        Objects.requireNonNull(acceleration, "acceleration");
        Objects.requireNonNull(contributions, "contributions");
        if (!isFinite(samplePoint)) {
            throw new IllegalArgumentException(
                    "samplePoint must be finite: " + samplePoint
            );
        }
        if (!isFinite(acceleration)) {
            throw new IllegalArgumentException(
                    "acceleration must be finite: " + acceleration
            );
        }
        contributions = List.copyOf(contributions);
    }

    /**
     * True when at least one field contributed to the effective composition
     * group, independent of the resultant magnitude.
     */
    public boolean fieldPresent() {
        return !contributions.isEmpty();
    }

    public double accelerationMagnitude() {
        return acceleration.length();
    }

    public boolean hasAcceleration() {
        return acceleration.lengthSquared() > 0.0D;
    }

    /** Unit direction of the resultant acceleration, when there is one. */
    public Optional<Vec3d> down() {
        if (!hasAcceleration()) {
            return Optional.empty();
        }
        return Optional.of(acceleration.normalized());
    }

    static ComposedGravitySample fromInternal(GravitySample sample, cc.sighs.gravityengine.api.field.FieldCoverage coverage) {
        Objects.requireNonNull(sample, "sample");
        return new ComposedGravitySample(
                sample.samplePoint(),
                sample.accelerationVector(),
                sample.contributions()
                        .stream()
                        .map(GravityContributionView::fromInternal)
                        .toList(), coverage
        );
    }

    private static boolean isFinite(Vec3d value) {
        return value.isFinite();
    }
}
