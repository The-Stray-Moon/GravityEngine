package com.example.examplemod.gravity;

import cc.sighs.gravityengine.api.*;
import cc.sighs.gravityengine.api.field.GravityField;
import cc.sighs.gravityengine.api.field.GravityFieldCompositionMode;
import cc.sighs.gravityengine.api.field.GravityFields;
import cc.sighs.gravityengine.api.field.GravityInfluenceVolume;
import cc.sighs.gravityengine.api.math.Vec3d;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.Optional;

/**
 * Compile-only fixture for a fictitious external content mod.
 *
 * <p>This class exists to prove that a consumer can publish, sample and
 * inspect gravity through {@code cc.sighs.gravityengine.api...} alone. It is
 * never registered, never loaded and never executed; it only has to compile.
 * A real external mod would keep the same shape.</p>
 *
 * <p>One instance of this class represents one placed star-core block. It
 * owns at most one {@link FieldPublication} lease: it retains the lease after
 * publishing, replaces it only when a newer revision is accepted, and closes
 * the lease it owns when the block disappears. A stale or dropped handle can
 * therefore never remove a newer registration.</p>
 */
public final class ExternalConsumerFixture {
    private static final ResourceLocation MASS_FIELD_TYPE = ResourceLocation
            .fromNamespaceAndPath("examplemod", "star_core_gravity");

    /*
     * No canonical gravitational constant is shipped by GravityEngine, so the
     * producer owns this value and reuses it for the field and its cutoff.
     */
    private static final double GRAVITY_CONSTANT = 6.0E-11D;
    private static final double MASS = 1.0E12D;
    private static final double SURFACE_RADIUS = 32.0D;

    private final BlockPos position;

    /** Currently owned lease, or {@code null} when this block publishes nothing. */
    private FieldPublication publication;

    public ExternalConsumerFixture(BlockPos position) {
        this.position = Objects.requireNonNull(position, "position").immutable();
    }

    public BlockPos position() {
        return position;
    }

    /** Stable per-instance publication identity for this block. */
    public ResourceLocation instanceId() {
        return GravityFieldIds.blockInstance(MASS_FIELD_TYPE, position);
    }

    public boolean published() {
        return publication != null;
    }

    /**
     * Attempts to publish the given revision.
     *
     * <p>If the submission is accepted, this fixture takes ownership of the new
     * lease and closes the previously owned lease afterwards. If the submission
     * is rejected as stale, the currently owned lease is left unchanged.</p>
     *
     * <p>The returned handle always describes this publication attempt. A
     * rejected handle owns nothing and is already logically closed, so callers
     * can inspect {@link FieldPublication#accepted()} or
     * {@link FieldPublication#status()} without confusing it with the lease
     * retained by this fixture.</p>
     */
    public FieldPublication publish(Level level, long revision) {
        FieldPublication next = GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                definition(revision)
        );

        if (!next.accepted()) {
            return next;
        }

        FieldPublication previous = publication;
        publication = next;

        if (previous != null) {
            previous.close();
        }

        return next;
    }

    /** Mod initialization example: factory constructs, onOpen publishes, close releases. */
    public static void registerInitialField(ResourceLocation provider, GravityFieldDefinition definition) {
        GravityEngineApi.registerFieldProvider(provider, level -> new GravityFieldProvider() {
            private FieldPublication initial;

            @Override public void onOpen() {
                initial = GravityEngineApi.publish(level, provider, definition);
            }

            @Override public cc.sighs.gravityengine.api.field.GravityFieldProviderResult evaluate(
                    cc.sighs.gravityengine.api.field.GravityFieldQuery query) {
                // This example owns exactly one fixed source: discovery is complete for every query.
                return new cc.sighs.gravityengine.api.field.GravityFieldProviderResult(
                        cc.sighs.gravityengine.api.field.FieldCoverage.COMPLETE,
                        GravityEngineApi.samplePublications(level, provider, query));
            }

            @Override public void close() {
                if (initial != null) { initial.close(); initial = null; }
            }
        });
    }

    /** Closes the owned lease; the field disappears from the level. */
    public void remove() {
        FieldPublication current = publication;
        if (current != null) {
            current.close();
            publication = null; // retain the lease if thread/phase validation rejects close
        }
    }

    /** Samples composed gravity around this block through the supported API. */
    public ComposedGravitySample sample(Level level) {
        Vec3d at = new Vec3d(
                position.getX() + 0.5D,
                position.getY() + 16.0D,
                position.getZ() + 0.5D
        );
        ComposedGravitySample sample = GravityEngineApi.sample(
                level,
                at,
                Vec3d.ZERO,
                level.getGameTime(),
                1.0D
        );
        if (sample.coverage() == cc.sighs.gravityengine.api.field.FieldCoverage.COMPLETE && sample.fieldPresent()) {
            double magnitude = sample.accelerationMagnitude();
            sample.down().ifPresent(direction ->
                    System.out.println(
                            "examplemod: star core gravity "
                                    + magnitude
                                    + " along "
                                    + direction
                    )
            );
        }
        return sample;
    }

    private GravityFieldDefinition definition(long revision) {
        Vec3d center = new Vec3d(
                position.getX() + 0.5D,
                position.getY() + 0.5D,
                position.getZ() + 0.5D
        );

        GravityField field = GravityFields.sphericalMass(
                center,
                MASS,
                GravityFields.uniformDensity(MASS, SURFACE_RADIUS),
                SURFACE_RADIUS,
                GRAVITY_CONSTANT
        );

        GravityInfluenceVolume influence = GravityFields.sphereInfluence(
                center,
                GravityFields.sphericalCutoffRadius(
                        GRAVITY_CONSTANT,
                        MASS,
                        SURFACE_RADIUS,
                        0.001D
                )
        );

        /*
         * The block factory derives the per-instance publication id from the
         * field type id and the block position, so two star cores never
         * replace or stale-reject each other.
         */
        return GravityFieldDefinition.block(
                MASS_FIELD_TYPE,
                position,
                field,
                influence,
                GravityFieldCompositionMode.OVERRIDE,
                revision
        );
    }

    public static void inspectPlayerGravity(Entity entity) {
        Optional<EntityGravitySnapshot> snapshot =
                GravityEngineApi.entityGravity(entity);
        snapshot.ifPresent(view -> {
            Vec3d direction = view.effectiveDirection();
            double strength = view.effectiveStrength();
            var fieldPresence = view.fieldPresence();
            view.referenceFrame().ifPresent(frame ->
                    System.out.println(
                            "examplemod: frame up " + frame.up()
                    )
            );
            System.out.println(
                    "examplemod: gravity " + direction.multiply(strength)
                            + " fieldPresence=" + fieldPresence
            );
        });
    }

    /** Environmental/control evidence; consumers must not use this as a collider axis. */
    public static Optional<Vec3d> environmentalUp(Entity entity) {
        return GravityEngineApi.entityGravity(entity).flatMap(EntityGravitySnapshot::referenceFrame)
                .map(cc.sighs.gravityengine.api.GravityFrameView::up);
    }

    /**
     * Compile-level proof that consumers preserve all three presence states
     * when building an
     * {@link EntityGravitySnapshot} directly.
     */
    public static EntityGravitySnapshot copySnapshot(
            EntityGravitySnapshot source
    ) {
        return new EntityGravitySnapshot(
                source.authority(),
                source.effectiveDirection(),
                source.effectiveStrength(),
                source.appliedDirection(),
                source.appliedStrength(),
                source.fieldPresence(),
                source.applicationEpoch(),
                source.referenceFrame(),
                source.assignedDirection(), source.assignedStrength(), source.observationSource()
        );
    }

    /** Register on both sides during mod initialization. Exact type wins;
     * the decision can change with entity state without a type-result cache. */
    public static void registerEntityAdapters() {
        // The control player's travel override records inputs, then delegates to Vanilla.
        GravityEngineApi.registerEntityAdapter(net.minecraft.world.entity.EntityType.PLAYER,
                player -> cc.sighs.gravityengine.api.EntityGravityAdapter.CHARACTER);
        GravityEngineApi.registerEntityAdapter(net.minecraft.world.entity.EntityType.PIG,
                pig -> pig.getTags().contains("examplemod:native")
                        ? cc.sighs.gravityengine.api.EntityGravityAdapter.NATIVE
                        : cc.sighs.gravityengine.api.EntityGravityAdapter.CHARACTER);
        boolean rejected = false;
        try { GravityEngineApi.registerEntityAdapter(net.minecraft.world.entity.EntityType.PIG,
                pig -> cc.sighs.gravityengine.api.EntityGravityAdapter.NATIVE); }
        catch (IllegalArgumentException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("duplicate adapter accepted");
    }

    /** Zero-gravity override published as a named, non-positional source. */
    public static FieldPublication publishZeroGravityZone(
            Level level,
            ResourceLocation zoneId
    ) {
        return GravityEngineApi.publish(
                level, com.example.examplemod.gravity.ProviderFixture.ID,
                GravityFieldDefinition.named(
                        zoneId,
                        GravityFields.zeroGravity(),
                        GravityFields.infiniteInfluence(),
                        GravityFieldCompositionMode.OVERRIDE,
                        1L
                )
        );
    }
}
