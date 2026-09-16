package cc.sighs.gravityengine.gravity.minecraft.access;

import net.minecraft.world.entity.LivingEntity;

public interface GravityLivingAccess {
    static GravityLivingAccess cast(LivingEntity entity) {
        return (GravityLivingAccess) entity;
    }

    boolean gravityengine$isJumping();
    void gravityengine$setJumping(boolean jumping);
    int gravityengine$getJumpDelay();
    void gravityengine$setJumpDelay(int ticks);
    float gravityengine$getJumpPower();
    float gravityengine$getFrictionInfluencedSpeed(float friction);
    float gravityengine$getFlyingSpeed();
    boolean gravityengine$shouldDiscardFriction();
    float gravityengine$getMaxHeadRotationRelativeToBody();
}
