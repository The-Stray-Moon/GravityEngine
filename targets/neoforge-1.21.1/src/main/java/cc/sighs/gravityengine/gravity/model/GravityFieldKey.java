package cc.sighs.gravityengine.gravity.model;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.Objects;

/**
 * Stable identity of one registered gravity field.
 *
 * <p>The identity is intentionally independent from the concrete field
 * evaluator and from collision geometry. A block-backed gravity core is only
 * one possible producer of a field key; global, scripted, entity-backed or
 * synthetic fields may use their own ids.</p>
 *
 * <p>The dimension is part of the identity so accidental cross-level
 * registration can still be rejected by {@code GravityFieldIndex}.</p>
 */
public record GravityFieldKey(
        ResourceKey<Level> dimension,
        ResourceLocation id
) implements Comparable<GravityFieldKey> {

    public GravityFieldKey {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(id, "id");
    }

    @Override
    public int compareTo(GravityFieldKey other) {
        Objects.requireNonNull(other, "other");

        int dimensionOrder = this.dimension.location().toString()
                .compareTo(other.dimension.location().toString());
        if (dimensionOrder != 0) {
            return dimensionOrder;
        }

        return this.id.toString().compareTo(other.id.toString());
    }
}