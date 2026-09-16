package cc.sighs.gravityengine.gravity.integration;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.model.GravitySample;
import cc.sighs.gravityengine.gravity.movement.CharacterControlPlan;
import cc.sighs.gravityengine.gravity.movement.TravelCapturePlan;
import cc.sighs.gravityengine.gravity.runtime.GravityRuntimeState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public record GravityTravelContext(
        LivingEntity entity,
        Vec3 input,
        cc.sighs.gravityengine.gravity.integration.GravityOperation operation,
        CharacterControlPlan characterPlan,
        TravelCapturePlan capturePlan,
        cc.sighs.gravityengine.look.SemanticLookSnapshot look
) implements AutoCloseable {
    public GravityTravelContext {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(characterPlan, "characterPlan");
        Objects.requireNonNull(look, "look");
    }

    public GravityFrame frame() { return operation.frame(); }
    public GravitySample sample() { return operation.sample(); }
    public GravityRuntimeState runtime() { return operation.component().runtime(); }

    @Override
    public void close() {
        operation.close();
    }

}