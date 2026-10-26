package com.example.examplemod.gravity;

import cc.sighs.gravityengine.api.FieldPublication;
import cc.sighs.gravityengine.api.GravityEngineApi;
import cc.sighs.gravityengine.api.GravityFieldDefinition;
import cc.sighs.gravityengine.api.field.*;
import cc.sighs.gravityengine.api.math.Vec3d;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.Optional;

/**
 * Compile-only fixture: a fictitious external mod implementing the raw field
 * SPI itself instead of using the built-in factories.
 *
 * <p>It proves the SPI is self-contained: the custom field and the custom
 * influence volume below are implemented with
 * {@code cc.sighs.gravityengine.api...} only. No GravityEngine internal
 * package, and in particular no internal geometry type, is imported or
 * required. It is never registered or executed; it only has to compile.</p>
 */
public final class CustomSpiFixture {
    private static final ResourceLocation FIELD_TYPE = ResourceLocation
            .fromNamespaceAndPath("examplemod", "directional_zone");

    private CustomSpiFixture() {}

    /**
     * Custom evaluator: constant world-space acceleration everywhere in its
     * influence volume.
     */
    public static final class ConstantDirectionalField
            implements GravityField {
        private final Vec3d acceleration;

        public ConstantDirectionalField(
                Vec3d acceleration
        ) {
            this.acceleration =
                    Objects.requireNonNull(
                            acceleration,
                            "acceleration"
                    );

            if (!this.acceleration.isFinite()) {
                throw new IllegalArgumentException(
                        "acceleration must be finite: "
                                + this.acceleration
                );
            }
        }

        @Override
        public GravityFieldSample sample(
                GravityFieldQuery query
        ) {
            Objects.requireNonNull(query, "query");
            return new GravityFieldSample(acceleration);
        }
    }

    /**
     * Custom finite influence: an inclusive axis-aligned box described by the
     * public {@link GravityFieldBounds} value type.
     */
    public static final class FiniteBoxInfluence
            implements GravityInfluenceVolume {
        private final GravityFieldBounds bounds;

        public FiniteBoxInfluence(GravityFieldBounds bounds) {
            this.bounds = Objects.requireNonNull(bounds, "bounds");
        }

        @Override
        public boolean contains(Vec3d position) {
            Objects.requireNonNull(position, "position");
            return position.x() >= bounds.minX()
                    && position.x() <= bounds.maxX()
                    && position.y() >= bounds.minY()
                    && position.y() <= bounds.maxY()
                    && position.z() >= bounds.minZ()
                    && position.z() <= bounds.maxZ();
        }

        @Override
        public Optional<GravityFieldBounds> finiteBounds() {
            return Optional.of(bounds);
        }
    }

    /** Publishes the custom SPI through the supported target-side API. */
    public static FieldPublication publish(
            Level level,
            BlockPos position,
            Vec3d acceleration,
            long revision
    ) {
        GravityFieldBounds bounds = new GravityFieldBounds(
                position.getX() - 8.0D,
                position.getY() - 8.0D,
                position.getZ() - 8.0D,
                position.getX() + 8.0D,
                position.getY() + 8.0D,
                position.getZ() + 8.0D
        );

        return GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                GravityFieldDefinition.block(
                        FIELD_TYPE,
                        position,
                        new ConstantDirectionalField(acceleration),
                        new FiniteBoxInfluence(bounds),
                        GravityFieldCompositionMode.ADDITIVE,
                        revision
                )
        );
    }

    public static void unpublish(FieldPublication publication) {
        publication.close();
    }
}
