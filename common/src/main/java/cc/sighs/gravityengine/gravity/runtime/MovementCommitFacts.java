package cc.sighs.gravityengine.gravity.runtime;

import cc.sighs.gravityengine.gravity.collision.CellPos;
import java.util.Objects;
import java.util.Optional;

/**
 * Loader-neutral movement/contact facts produced by one committed physical
 * move.
 *
 * <p>This is the generic form of the Vanilla ground/collision field
 * projection. It carries only facts that still have a complete meaning
 * without Minecraft: gameplay grounding, tangent/vertical blocking, the
 * path-contact supporting flag, and the supporting cell address.</p>
 *
 * <p>Platform projection is deliberately absent. A target maps these facts
 * (plus any target-only compatibility evidence) into its own vanilla
 * collision state at the Minecraft boundary; the common runtime never
 * constructs a platform value.</p>
 */
public record MovementCommitFacts(
        boolean grounded,
        boolean blockedTangent,
        boolean blockedVertical,
        boolean supportingContactDuringMove,
        Optional<CellPos> mainSupportingBlock
) {
    public MovementCommitFacts {
        Objects.requireNonNull(
                mainSupportingBlock,
                "mainSupportingBlock"
        );
        mainSupportingBlock = mainSupportingBlock.map(
                Objects::requireNonNull
        );
    }

    /** No authoritative move facts: the projection is non-grounded and empty. */
    public static final MovementCommitFacts NONE =
            new MovementCommitFacts(
                    false,
                    false,
                    false,
                    false,
                    Optional.empty()
            );
}
