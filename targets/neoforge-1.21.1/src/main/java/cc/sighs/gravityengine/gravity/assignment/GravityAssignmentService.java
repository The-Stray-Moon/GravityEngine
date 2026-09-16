package cc.sighs.gravityengine.gravity.assignment;

import cc.sighs.gravityengine.gravity.GravityState;
import cc.sighs.gravityengine.gravity.field.GravityFieldService;
import cc.sighs.gravityengine.gravity.minecraft.access.GravityEntityAccess;
import cc.sighs.gravityengine.gravity.minecraft.geometry.GravityEntityGeometry;
import cc.sighs.gravityengine.gravity.model.GravitySample;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** Evaluates composed source gravity without mutating or synchronizing entities. */
public final class GravityAssignmentService {
    private static final double ACCELERATION_EPSILON_SQUARED = 1.0E-12D;

    private GravityAssignmentService() {}

    public record AssignmentResult(
            GravityState previous,
            GravityState resolved,
            boolean fieldPresent,
            boolean changed
    ) {
        public AssignmentResult {
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(resolved, "resolved");
        }
    }

    public static AssignmentResult evaluate(Entity entity) {
        Objects.requireNonNull(entity, "entity");

        var access = GravityEntityAccess.cast(entity);
        var component = access.gravityengine$gravityComponent();
        GravityState previous = component.assignedState();
        boolean previousFieldPresent = component.assignedFieldPresent();
        var installed = access
                .gravityengine$gravityComponent().runtime().geometryReferenceFrame();
        Vec3 samplePoint = installed == null
                ? GravityEntityGeometry.proxyCenter(entity)
                : GravityEntityGeometry.bodyCenter(entity, installed);
        GravitySample sample = GravityFieldService.sample(
                entity.level(), samplePoint);
        GravityState resolved = resolve(previous, sample);
        boolean fieldPresent = sample.hasActiveField();

        return new AssignmentResult(
                previous,
                resolved,
                fieldPresent,
                !previous.sameSyncData(resolved)
                        || previousFieldPresent != fieldPresent
        );
    }

    public static GravityState resolve(GravityState previous, GravitySample sample) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(sample, "sample");

        if (sample.contributions().isEmpty()) {
            return GravityState.DEFAULT;
        }

        Vec3 acceleration = sample.accelerationVector();
        double magnitudeSquared = acceleration.lengthSqr();
        if (magnitudeSquared >= ACCELERATION_EPSILON_SQUARED) {
            return new GravityState(
                    acceleration.scale(1.0D / Math.sqrt(magnitudeSquared)),
                    Math.sqrt(magnitudeSquared)
            );
        }

        // Active sources cancel. Preserve the previous down direction while
        // publishing zero force. Full basis preservation belongs to the
        // runtime-frame phase.
        return new GravityState(previous.down(), 0.0D);
    }
}