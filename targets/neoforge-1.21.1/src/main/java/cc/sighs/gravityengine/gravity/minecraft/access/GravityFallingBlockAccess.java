package cc.sighs.gravityengine.gravity.minecraft.access;

import cc.sighs.gravityengine.gravity.integration.FallingBlockBallisticSnapshot;

/** Target-internal access to the entity-owned, server-authored ballistic result. */
public interface GravityFallingBlockAccess {
    FallingBlockBallisticSnapshot gravityengine$ballisticSnapshot();
    void gravityengine$publishBallisticSnapshot(FallingBlockBallisticSnapshot snapshot);
}
