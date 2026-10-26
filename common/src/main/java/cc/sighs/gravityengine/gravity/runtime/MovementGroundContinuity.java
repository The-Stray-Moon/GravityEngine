package cc.sighs.gravityengine.gravity.runtime;

/**
 * Accepted movement-ground facts available to a later physical transaction
 * in the same game tick.
 *
 * <p>This remains same-tick movement continuity, not a cross-tick jump-grace
 * state machine. It now deliberately also carries the minimum terminal-support
 * presence required by a later same-tick physical transaction:
 * {@code terminalSupported} is the determinate physical endpoint support of
 * the committed move. It still never carries a block identity, material,
 * support normal, platform velocity or a second support state machine.</p>
 *
 * <p>{@code gameplayGrounded=true, terminalSupported=false} is a valid
 * published state: a move may retain Vanilla jump/edge continuity from
 * supporting contact while the resolved endpoint itself has no terminal
 * support. Consumers must never reconstruct {@code terminalSupported} from
 * {@code Entity.onGround()} or {@code gameplayGrounded}.</p>
 */
public record MovementGroundContinuity(
        long gameTick,
        boolean supportingContactDuringMove,
        boolean gameplayGrounded,
        boolean terminalSupported
) {
    public MovementGroundContinuity {
        if (supportingContactDuringMove && !gameplayGrounded) {
            throw new IllegalArgumentException("supporting movement must establish gameplay ground");
        }
        if (terminalSupported && !gameplayGrounded) {
            throw new IllegalArgumentException(
                    "terminal support requires gameplay ground");
        }
    }
}
