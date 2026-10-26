package cc.sighs.gravityengine.gravity.acceleration;

import cc.sighs.gravityengine.api.field.GravityFieldQuery;
import cc.sighs.gravityengine.api.field.FieldCoverage;
import cc.sighs.gravityengine.api.math.Vec3d;
import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.field.GravityFieldEvaluationSource;
import cc.sighs.gravityengine.gravity.field.GravityFieldService;
import cc.sighs.gravityengine.gravity.model.GravityAccelerationMode;
import cc.sighs.gravityengine.gravity.model.GravityApplicationPlan;
import cc.sighs.gravityengine.gravity.model.GravitySample;
import java.util.Objects;
import java.util.Optional;

/**
 * Canonical gravity-evaluation boundary.
 *
 * <p>Every physics boundary that needs acceleration produces exactly one
 * {@link GravityEvaluationSnapshot} here. A snapshot is bound to the complete
 * physical {@link GravityEvaluationContext}; authority alone is never enough
 * to reuse or promote it.</p>
 *
 * <p>Loader-neutral: the world scope is the owning
 * {@link GravityFieldEvaluationSource}, never a platform level.</p>
 */
public final class GravityEvaluationService {
    private GravityEvaluationService() {}

    /**
     * Character-body evidence resolution for one already-committed plan.
     */
    public static GravitySample characterEvidence(
            AccelerationQuery query,
            GravityApplicationPlan plan,
            GravityFieldEvaluationSource registry
    ) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(plan, "plan");

        GravityState applied = query.appliedGravity();

        return switch (plan.accelerationMode()) {
            case NONE, DIRECT ->
                    GravitySample.fromState(
                            applied,
                            query.samplePoint()
                    );

            case FIELD ->
                    GravityAccelerationResolver.selectCharacterFieldSample(
                            sampleField(query, registry),
                            applied,
                            query.samplePoint()
                    );
        };
    }

    /**
     * Plan-generic evidence resolution.
     */
    public static GravitySample fieldEvidence(
            AccelerationQuery query,
            GravityApplicationPlan plan,
            GravityFieldEvaluationSource registry
    ) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(plan, "plan");

        return switch (plan.accelerationMode()) {
            case NONE ->
                    GravitySample.zero(query.samplePoint());

            case FIELD ->
                    sampleField(query, registry);

            case DIRECT ->
                    GravitySample.fromState(
                            query.appliedGravity(),
                            query.samplePoint()
                    );
        };
    }

    /**
     * Samples raw composed field evidence for assignment resolution.
     *
     * <p>This is not a physical runtime snapshot and must not be promoted as
     * one.</p>
     */
    public static GravityFieldEvaluation sampleFieldEvaluation(
            AccelerationQuery query,
            GravityFieldEvaluationSource registry
    ) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(registry, "registry");

        return registry.evaluate(new GravityFieldQuery(query.samplePoint(), query.velocity(),
                query.gameTick(), query.intervalTicks()));
    }

    /**
     * Canonical character-operation evaluation for the committed context.
     */
    public static GravityEvaluationSnapshot evaluateCharacterOperation(
            GravityEvaluationContext context,
            GravityFieldEvaluationSource registry,
            AccelerationQuery query,
            Vec3d fallbackDown,
            GravityFrame previousFrame
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(query, "query");

        GravityApplicationPlan plan =
                context.committedApplication().plan();
        requireContextRegistryRevision(context, plan, registry);

        GravityFieldEvaluation field = plan.accelerationMode() == GravityAccelerationMode.FIELD
                ? sampleFieldEvaluation(query, registry) : null;
        GravitySample evidence = field == null ? characterEvidence(query, plan, registry)
                : field.coverage() == FieldCoverage.INCOMPLETE
                ? GravitySample.fromState(query.appliedGravity(), query.samplePoint())
                : GravityAccelerationResolver.selectCharacterFieldSample(field.sample(), query.appliedGravity(), query.samplePoint());
        return snapshot(context, query, evidence, fallbackDown, previousFrame)
                .withFieldCoverage(field == null ? FieldCoverage.COMPLETE : field.coverage());
    }

    /**
     * Plan-generic evaluation for the committed context.
     */
    public static GravityEvaluationSnapshot evaluateForPlan(
            GravityEvaluationContext context,
            GravityFieldEvaluationSource registry,
            AccelerationQuery query,
            Vec3d fallbackDown,
            GravityFrame previousFrame
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(query, "query");

        GravityApplicationPlan plan =
                context.committedApplication().plan();
        requireContextRegistryRevision(context, plan, registry);

        GravityFieldEvaluation field = plan.accelerationMode() == GravityAccelerationMode.FIELD
                ? sampleFieldEvaluation(query, registry) : null;
        GravitySample evidence = field == null ? fieldEvidence(query, plan, registry)
                : field.coverage() == FieldCoverage.INCOMPLETE
                ? GravitySample.fromState(query.appliedGravity(), query.samplePoint())
                : field.sample();
        return snapshot(context, query, evidence, fallbackDown, previousFrame)
                .withFieldCoverage(field == null ? FieldCoverage.COMPLETE : field.coverage());
    }

    /**
     * Builds a physical snapshot from raw field evidence already sampled for
     * assignment resolution.
     *
     * <p>The raw sample is used only when the actually committed plan is
     * FIELD. If the committed application still applies DIRECT or NONE, this
     * method builds that physical truth instead of pretending the pending
     * field assignment is already active.</p>
     */
    public static GravityEvaluationSnapshot snapshotFromFieldEvidence(
            GravityEvaluationContext context,
            AccelerationQuery query,
            GravitySample rawFieldEvidence,
            Vec3d fallbackDown,
            GravityFrame previousFrame
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(rawFieldEvidence, "rawFieldEvidence");
        if (!rawFieldEvidence.samplePoint().equals(
                query.samplePoint()
        )) {
            throw new IllegalArgumentException(
                    "raw field evidence sample point does not match "
                            + "the physical query"
            );
        }

        GravityApplicationPlan plan =
                context.committedApplication().plan();
        GravitySample evidence =
                physicalEvidence(
                        context,
                        query,
                        plan,
                        rawFieldEvidence
                );

        return snapshot(
                context,
                query,
                evidence,
                fallbackDown,
                previousFrame
        );
    }

    /**
     * Builds the committed physical snapshot from assignment-time raw field
     * evidence.
     *
     * <p>Raw evidence is reused only while the committed physical query and
     * the FIELD registry generation are unchanged. A geometry recovery or any
     * other transition that changed the committed evaluation point produces
     * exactly one new evaluation at the committed query.</p>
     */
    public static GravityEvaluationSnapshot evaluateCommitted(
            GravityEvaluationContext context,
            GravityFieldEvaluationSource registry,
            AccelerationQuery query,
            GravityFieldEvaluation sampledEvidence,
            Vec3d fallbackDown,
            GravityFrame previousFrame
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(
                sampledEvidence,
                "sampledEvidence"
        );

        boolean queryUnchanged = sameQuery(
                sampledEvidence.query(),
                query
        );
        boolean registryUnchanged =
                !context.usesFieldRegistry()
                        || sampledEvidence.fieldRegistryRevision()
                        == context.fieldRegistryRevision();

        if (!queryUnchanged || !registryUnchanged
                || context.usesFieldRegistry() && !registry.revisionCoversEvaluation()) {
            return evaluateCharacterOperation(
                    context,
                    registry,
                    query,
                    fallbackDown,
                    previousFrame
            );
        }

        return snapshotFromFieldEvidence(
                context,
                query,
                sampledEvidence.coverage() == FieldCoverage.COMPLETE ? sampledEvidence.sample()
                        : GravitySample.fromState(query.appliedGravity(), query.samplePoint()),
                fallbackDown,
                previousFrame
        ).withFieldCoverage(sampledEvidence.coverage());
    }

    /**
     * Reuse policy for a tick/assignment snapshot inside a later movement
     * operation. Reuse requires the complete query and the complete physical
     * evaluation context to be unchanged.
     */
    public static Optional<GravityEvaluationSnapshot> reusable(
            GravityEvaluationSnapshot candidate,
            GravityEvaluationContext currentContext,
            AccelerationQuery query
    ) {
        if (candidate == null
                || currentContext == null
                || query == null
                || !candidate.matchesInputs(
                query.samplePoint(),
                query.velocity(),
                query.gameTick(),
                query.intervalTicks()
        )
                || !candidate.context()
                .samePhysicalContext(currentContext)) {
            return Optional.empty();
        }

        /*
         * Complete query equality + complete physical-context equality is the
         * authoritative reuse condition.
         *
         * An inactive FIELD result and a NONE result are still real physical
         * results. Re-evaluating them merely because acceleration is currently
         * zero creates a second gravity truth inside the same logical tick.
         */
        return Optional.of(candidate);
    }

    private static GravityEvaluationSnapshot snapshot(
            GravityEvaluationContext context,
            AccelerationQuery query,
            GravitySample evidence,
            Vec3d fallbackDown,
            GravityFrame previousFrame
    ) {
        GravityApplicationPlan plan =
                context.committedApplication().plan();
        GravitySample acceleration =
                GravityAccelerationResolver.characterAcceleration(
                        evidence,
                        plan
                );
        GravityFrame frame =
                GravityFrame.fromEnvironmentalEvidence(
                        evidence.samplePoint(),
                        evidence.accelerationVector(),
                        fallbackDown,
                        previousFrame
                );

        return new GravityEvaluationSnapshot(
                context,
                new GravityFieldQuery(
                        query.samplePoint(),
                        query.velocity(),
                        query.gameTick(),
                        query.intervalTicks()
                ),
                evidence,
                acceleration,
                frame,
                query.gameTick(),
                query.intervalTicks()
        );
    }

    private static GravitySample physicalEvidence(
            GravityEvaluationContext context,
            AccelerationQuery query,
            GravityApplicationPlan plan,
            GravitySample rawFieldEvidence
    ) {
        return switch (plan.accelerationMode()) {
            case NONE -> GravitySample.zero(query.samplePoint());
            case DIRECT -> GravitySample.fromState(
                    context.committedApplication().appliedState(),
                    query.samplePoint()
            );
            case FIELD -> {
                if (rawFieldEvidence.hasActiveField()) {
                    yield rawFieldEvidence;
                }
                GravityState applied =
                        context.committedApplication().appliedState();
                yield applied.isDefault()
                        ? rawFieldEvidence
                        : GravitySample.fromState(
                                applied,
                                query.samplePoint()
                        );
            }
        };
    }

    private static boolean sameQuery(
            GravityFieldQuery sampled,
            AccelerationQuery committed
    ) {
        return sampled.gameTick() == committed.gameTick()
                && Double.compare(
                        sampled.intervalTicks(),
                        committed.intervalTicks()
                ) == 0
                && sampled.position().equals(committed.samplePoint())
                && sampled.velocity().equals(committed.velocity());
    }

    private static void requireContextRegistryRevision(
            GravityEvaluationContext context,
            GravityApplicationPlan plan,
            GravityFieldEvaluationSource registry
    ) {
        long expected = registryRevision(plan, registry);
        if (context.fieldRegistryRevision() != expected) {
            throw new IllegalArgumentException(
                    "evaluation context registry revision does not match "
                            + "the registry used for evaluation: context="
                            + context.fieldRegistryRevision()
                            + " current=" + expected
            );
        }
    }

    private static long registryRevision(
            GravityApplicationPlan plan,
            GravityFieldEvaluationSource registry
    ) {
        if (plan.accelerationMode() != GravityAccelerationMode.FIELD) {
            return GravityEvaluationSnapshot.NO_FIELD_REGISTRY_REVISION;
        }
        return Objects.requireNonNull(registry, "registry")
                .publicationRevision();
    }

    private static GravitySample sampleField(
            AccelerationQuery query,
            GravityFieldEvaluationSource registry
    ) {
        Objects.requireNonNull(registry, "registry");

        var result = sampleFieldEvaluation(query, registry);
        return result.coverage() == FieldCoverage.COMPLETE ? result.sample()
                : GravitySample.fromState(query.appliedGravity(), query.samplePoint());
    }
}
