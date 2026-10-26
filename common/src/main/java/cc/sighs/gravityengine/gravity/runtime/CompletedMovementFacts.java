package cc.sighs.gravityengine.gravity.runtime;

import cc.sighs.gravityengine.gravity.GravityFrame;
import cc.sighs.gravityengine.gravity.collision.GravitySupportContact;
import java.util.Optional;

/** Detached completion of one outer movement. Owned by its closed scope, never
 * cached on the entity. No scene, query budget or full move result escapes. */
public record CompletedMovementFacts(
        GravityFrame referenceFrame,
        double fallVertical,
        boolean gameplayGrounded,
        boolean terminalSupported,
        boolean physicalLanding,
        Optional<GravitySupportContact> terminalContact
) {
    /** A real path landing clears accumulated fall even after leaving its edge;
     * sustained terminal support also clears it on subsequent stationary moves. */
    public boolean fallDamageGrounded() {
        return physicalLanding || terminalSupported;
    }
}
