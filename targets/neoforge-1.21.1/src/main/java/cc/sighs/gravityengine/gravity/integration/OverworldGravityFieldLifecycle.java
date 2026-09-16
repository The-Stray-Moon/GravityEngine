package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.GravityEngine;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.field.*;
import cc.sighs.gravityengine.gravity.model.GravityFieldKey;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Minecraft-aware producer/lifecycle adapter that registers the canonical
 * Overworld base field as one stable global field on both logical sides at
 * level load.
 *
 * <p>The registration uses a {@link HeightGravityField} with
 * {@link InfiniteInfluenceVolume} and
 * {@link GravityFieldCompositionMode#ADDITIVE}. It is present at every
 * height, including Y >= 300 where the evaluator contributes a zero vector.
 * Y <= 200 is not a Vanilla fallback: it is an explicit active field
 * contribution of {@code (0, -0.08, 0)}.</p>
 *
 * <p>This field is not block-backed. Unload cleanup is owned by removal of
 * the level-local {@link GravityFieldRuntime}; this adapter only supplies
 * registration.</p>
 */
public final class OverworldGravityFieldLifecycle {
    public static final double FULL_GRAVITY_Y = 100.0D;
    public static final double ZERO_GRAVITY_Y = 150.0D;

    private static final ResourceLocation FIELD_ID = ResourceLocation.fromNamespaceAndPath(
            GravityEngine.MOD_ID, "overworld_height_gravity");

    private OverworldGravityFieldLifecycle() {}

    public static void register(Level level) {
        if (!level.dimension().equals(Level.OVERWORLD)) {
            return;
        }
        GravityFieldRuntime.get(level).put(createInstance(level.dimension()));
    }

    static GravityFieldInstance createInstance(ResourceKey<Level> dimension) {
        return new GravityFieldInstance(
                new GravityFieldKey(dimension, FIELD_ID),
                GravityFieldOrder.named((new GravityFieldKey(dimension, FIELD_ID)).id()),
                new HeightGravityField(FULL_GRAVITY_Y, ZERO_GRAVITY_Y, GravityState.VANILLA_STRENGTH),
                InfiniteInfluenceVolume.INSTANCE,
                GravityFieldCompositionMode.ADDITIVE,
                1L);
    }
}
