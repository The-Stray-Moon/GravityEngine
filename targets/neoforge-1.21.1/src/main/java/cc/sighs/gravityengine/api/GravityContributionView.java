package cc.sighs.gravityengine.api;

import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.model.GravityContribution;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * One field instance's immutable contribution to a composed sample.
 *
 * <p>The acceleration is the actual world-space vector produced by that
 * instance's evaluator at the sample point. A zero vector is a real
 * contribution: presence is contribution identity, never magnitude.</p>
 */
public record GravityContributionView(
        ResourceLocation fieldId,
        long revision,
        Vec3d acceleration
) {
    public GravityContributionView {
        Objects.requireNonNull(fieldId, "fieldId");
        Objects.requireNonNull(acceleration, "acceleration");
        if (revision < 0L) {
            throw new IllegalArgumentException(
                    "revision must be non-negative: " + revision
            );
        }
    }

    static GravityContributionView fromInternal(
            GravityContribution contribution
    ) {
        return new GravityContributionView(
                MinecraftMathAdapter.toResourceLocation(
                        contribution.source()),
                contribution.revision(),
                contribution.accelerationVector()
        );
    }
}
