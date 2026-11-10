package cc.sighs.gravityengine.gravity.minecraft.access;

/** Entity-owned native load receipt and pending authoritative metadata. No global entity retention. */
public interface GravityArmorStandAccess {
    boolean gravityengine$nativeNoPhysics();
    boolean gravityengine$pendingDimensions();
    void gravityengine$clearPendingDimensions();
}
