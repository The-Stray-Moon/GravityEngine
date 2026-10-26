package cc.sighs.gravityengine.api;

import cc.sighs.gravityengine.api.field.GravityField;
import cc.sighs.gravityengine.api.field.GravityFieldCompositionMode;
import cc.sighs.gravityengine.api.field.GravityInfluenceVolume;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * Everything a producer legitimately owns about one published gravity field.
 *
 * <p>The dimension is deliberately absent: it is taken from the
 * {@code Level} passed to
 * {@link GravityEngineApi#publish(net.minecraft.world.level.Level,
 * net.minecraft.resources.ResourceLocation, GravityFieldDefinition)}, so a producer can never publish a descriptor
 * whose dimension disagrees with its target level.</p>
 *
 * <p>Fields:</p>
 * <ul>
 *     <li>{@code id} - stable publication <em>instance</em> identity inside
 *     the level. Publishing a higher {@code revision} for the same id
 *     replaces the previous registration, and revisions are only ever
 *     compared between submissions that share this id. Two simultaneous
 *     block-backed fields must therefore use distinct ids; use
 *     {@link #block} (or {@link GravityFieldIds#blockInstance}) rather than
 *     reusing one field-type id for every block.</li>
 *     <li>{@code source} - stable structural accumulation order; never
 *     registration time, object identity or revision.</li>
 *     <li>{@code field} - pure evaluator.</li>
 *     <li>{@code influence} - where the field participates at all.</li>
 *     <li>{@code compositionMode} - ADDITIVE or OVERRIDE.</li>
 *     <li>{@code revision} - monotonic non-negative revision owned by the
 *     producer. Revisions at or below the currently registered one are
 *     rejected as stale.</li>
 * </ul>
 *
 * <p>The factories below are the recommended way to build a definition. They
 * keep the publication instance id, the source order and the field type
 * identity consistent with each other instead of leaving the producer to
 * assemble three related values by hand.</p>
 */
public record GravityFieldDefinition(
        ResourceLocation id,
        GravityFieldSource source,
        GravityField field,
        GravityInfluenceVolume influence,
        GravityFieldCompositionMode compositionMode,
        long revision
) {
    public GravityFieldDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(influence, "influence");
        Objects.requireNonNull(compositionMode, "compositionMode");
        if (revision < 0L) {
            throw new IllegalArgumentException(
                    "revision must be non-negative: " + revision
            );
        }
    }

    /**
     * Named/non-positional field whose publication instance id is the field
     * type id itself, because only one instance of it can sensibly exist.
     */
    public static GravityFieldDefinition named(
            ResourceLocation fieldTypeId,
            GravityField field,
            GravityInfluenceVolume influence,
            GravityFieldCompositionMode compositionMode,
            long revision
    ) {
        Objects.requireNonNull(fieldTypeId, "fieldTypeId");
        return new GravityFieldDefinition(
                fieldTypeId,
                GravityFieldSource.named(fieldTypeId),
                field,
                influence,
                compositionMode,
                revision
        );
    }

    /**
     * Block-backed field with a per-instance publication id derived from the
     * field type id and the block position.
     *
     * <p>Two blocks of the same field type at different positions therefore
     * register, revise and unregister independently. Revisions stay scoped to
     * one block instance.</p>
     */
    public static GravityFieldDefinition block(
            ResourceLocation fieldTypeId,
            BlockPos position,
            GravityField field,
            GravityInfluenceVolume influence,
            GravityFieldCompositionMode compositionMode,
            long revision
    ) {
        Objects.requireNonNull(fieldTypeId, "fieldTypeId");
        Objects.requireNonNull(position, "position");
        return new GravityFieldDefinition(
                GravityFieldIds.blockInstance(fieldTypeId, position),
                GravityFieldSource.block(fieldTypeId, position),
                field,
                influence,
                compositionMode,
                revision
        );
    }
}
