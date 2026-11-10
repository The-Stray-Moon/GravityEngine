package com.example.examplemod.gravity;

import cc.sighs.gravityengine.api.*;
import cc.sighs.gravityengine.api.field.*;
import cc.sighs.gravityengine.api.math.Vec3d;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** Compiled documentation example; not a test mod or automatic registration. */
public final class DocumentedApiExample {
    private static final ResourceLocation PROVIDER = ResourceLocation.parse("examplemod:fixed_domain");
    private static final ResourceLocation SOURCE = ResourceLocation.parse("examplemod:fixed_field");

    /** Invoke once during mod initialization, before server Levels exist. */
    public static void register() {
        GravityEngineApi.registerFieldProvider(PROVIDER, level -> new GravityFieldProvider() {
            private FieldPublication lease;

            @Override public void onOpen() {
                if (level.dimension().equals(Level.OVERWORLD)) {
                    lease = GravityEngineApi.publish(level, PROVIDER,
                            GravityFieldDefinition.named(SOURCE,
                                    query -> new GravityFieldSample(new Vec3d(0, -0.04, 0)),
                                    GravityFields.sphereInfluence(new Vec3d(0, 80, 0), 16),
                                    GravityFieldCompositionMode.ADDITIVE, 0));
                }
            }

            @Override public GravityFieldProviderResult evaluate(GravityFieldQuery query) {
                // This domain consists ONLY of the fixed source above. No chunks
                // or external source index need discovery. COMPLETE is justified.
                return new GravityFieldProviderResult(FieldCoverage.COMPLETE,
                        GravityEngineApi.samplePublications(level, PROVIDER, query));
            }

            @Override public void close() {
                if (lease != null) { lease.close(); lease = null; }
            }
        });
    }

    public static GravityMutationResult direct(net.minecraft.world.entity.Entity entity) {
        return GravityEngineApi.setDirectGravity(entity, new Vec3d(0, -1, 0), 0.04);
    }

    public static GravityMutationResult release(net.minecraft.world.entity.Entity entity) {
        return GravityEngineApi.releaseDirectGravity(entity);
    }

    public static ComposedGravitySample sample(Level level, Vec3d position, Vec3d velocity) {
        return GravityEngineApi.sample(level, position, velocity, level.getGameTime(), 1);
    }
}
