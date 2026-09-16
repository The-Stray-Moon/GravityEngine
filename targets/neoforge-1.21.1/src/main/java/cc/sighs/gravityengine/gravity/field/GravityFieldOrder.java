package cc.sighs.gravityengine.gravity.field;

import net.minecraft.resources.ResourceLocation;

import java.util.Comparator;
import java.util.Objects;

/** Structural accumulation order within a dimension: source type, then numeric
 * X/Y/Z. Named non-positional sources use zero coordinates. Registration ids
 * break structural ties only; changing a block field's id cannot reorder
 * distinct source positions. Neither revision nor registration timing orders fields. */
public record GravityFieldOrder(ResourceLocation sourceType, int x, int y, int z)
        implements Comparable<GravityFieldOrder> {
    private static final Comparator<GravityFieldOrder> ORDER =
            Comparator.comparing((GravityFieldOrder order) -> order.sourceType().toString())
                    .thenComparingInt(GravityFieldOrder::x)
                    .thenComparingInt(GravityFieldOrder::y)
                    .thenComparingInt(GravityFieldOrder::z);

    public GravityFieldOrder {
        Objects.requireNonNull(sourceType, "sourceType");
    }

    public static GravityFieldOrder named(ResourceLocation sourceType) {
        return new GravityFieldOrder(sourceType, 0, 0, 0);
    }

    @Override
    public int compareTo(GravityFieldOrder other) {
        return ORDER.compare(this, other);
    }
}
