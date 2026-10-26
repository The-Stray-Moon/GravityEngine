package cc.sighs.gravityengine.api;

import cc.sighs.gravityengine.gravity.field.GravityFieldOrder;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

/**
 * Stable structural identity of a published field's source, used for the
 * primary deterministic accumulation order.
 *
 * <p>Fields are ordered first by this source identity: source type, then
 * numeric block X/Y/Z. Named sources use zero coordinates. If two published
 * fields have the same source order, their stable publication key is used as
 * the final deterministic tie-break. Registration time, object identity,
 * hash-map iteration order and revision never affect accumulation order.</p>
 *
 * <p>This is <em>not</em> the publication instance identity. Registration,
 * replacement and revision scope belong to
 * {@link GravityFieldDefinition#id()}; see {@link GravityFieldIds}. Two
 * simultaneous block-backed fields need distinct publication ids even though
 * they intentionally share one source type and differ only by position.</p>
 *
 * <p>Use {@link #named} for a non-positional source (a scripted effect, a
 * global profile, an entity-backed source, ...). Use {@link #block} when the
 * field belongs to a specific block position, which gives every distinct
 * position its own stable place in the primary accumulation order.</p>
 */
public record GravityFieldSource(
        ResourceLocation sourceType,
        Optional<BlockPos> blockPosition
) {
    public GravityFieldSource {
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(blockPosition, "blockPosition");
        blockPosition = blockPosition.map(BlockPos::immutable);
    }

    /** Named/non-positional source. Accumulation coordinates are (0,0,0). */
    public static GravityFieldSource named(
            ResourceLocation sourceType
    ) {
        return new GravityFieldSource(
                sourceType,
                Optional.empty()
        );
    }

    /** Block-position-backed source. */
    public static GravityFieldSource block(
            ResourceLocation sourceType,
            BlockPos position
    ) {
        Objects.requireNonNull(position, "position");
        return new GravityFieldSource(
                sourceType,
                Optional.of(position.immutable())
        );
    }

    GravityFieldOrder toInternalOrder() {
        cc.sighs.gravityengine.gravity.model.GravityFieldId structuralId =
                MinecraftMathAdapter.toFieldId(sourceType);
        return blockPosition
                .map(position -> new GravityFieldOrder(
                        structuralId,
                        position.getX(),
                        position.getY(),
                        position.getZ()
                ))
                .orElseGet(() -> GravityFieldOrder.named(structuralId));
    }
}
