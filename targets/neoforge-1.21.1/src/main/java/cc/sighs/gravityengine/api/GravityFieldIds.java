package cc.sighs.gravityengine.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * Supported derivation of stable publication identities.
 *
 * <p>A field <em>type</em> identity and a field <em>publication instance</em>
 * identity are different things. The publication instance id is what
 * {@link GravityFieldDefinition#id()} registers under, and it is the scope of
 * revision monotonicity and replacement. The source/order identity is what
 * decides deterministic accumulation order.</p>
 *
 * <p>Two simultaneously active block-backed fields must not share a
 * publication id, or the second would replace (or be stale-rejected against)
 * the first. {@link #blockInstance} derives a distinct, deterministic
 * instance id from the field type id and the block position, so
 * {@code (baseId, (0,64,0))} and {@code (baseId, (100,64,0))} are independent
 * registrations while remaining stable across save/load.</p>
 *
 * <p>The derivation uses only the namespace, the base path and the integer
 * block coordinates. It never uses Java object identity, runtime hashes,
 * registration order or revision.</p>
 */
public final class GravityFieldIds {
    private GravityFieldIds() {}

    /**
     * Derives the publication instance id of one block-backed field.
     *
     * <p>Same base id and same position always produce the same result;
     * different positions always produce different results. Prefer
     * {@link GravityFieldDefinition#block} which applies this derivation for
     * you and keeps the id and the source order consistent.</p>
     *
     * @param fieldTypeId stable field/type identity, e.g.
     *                    {@code examplemod:star_core_gravity}
     * @param position    the block this field instance belongs to
     */
    public static ResourceLocation blockInstance(
            ResourceLocation fieldTypeId,
            BlockPos position
    ) {
        Objects.requireNonNull(fieldTypeId, "fieldTypeId");
        Objects.requireNonNull(position, "position");

        return ResourceLocation.fromNamespaceAndPath(
                fieldTypeId.getNamespace(),
                fieldTypeId.getPath()
                        + "/"
                        + position.getX()
                        + "_"
                        + position.getY()
                        + "_"
                        + position.getZ()
        );
    }
}
