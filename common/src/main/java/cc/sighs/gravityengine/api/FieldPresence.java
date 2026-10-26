package cc.sighs.gravityengine.api;

/**
 * Three-state FIELD contribution evidence for one entity assignment.
 *
 * <p>Contribution presence is identity evidence, not resultant magnitude: a
 * group of active fields that cancels to a zero vector is still
 * {@link #PRESENT}. The three states exist because "no field contribution was
 * observed" and "no field contribution can currently be proven absent" are
 * different facts:</p>
 *
 * <ul>
 *   <li>{@link #PRESENT} - a live field contribution was observed for this
 *   assignment in the current destination world;</li>
 *   <li>{@link #ABSENT} - FIELD absence is authoritative for this assignment:
 *   the complete query contains no active contribution;</li>
 *   <li>{@link #UNKNOWN} - current query coverage cannot prove the full contribution set.
 *   A retained durable assignment may provide application continuity.
 *   Consumers must choose their own explicit behaviour; UNKNOWN is never
 *   confirmed presence and never confirmed absence.</li>
 * </ul>
 *
 * <p>Loader-neutral: no Minecraft, loader, Mixin or optional-mod type may
 * appear here. This type is part of the supported platform-neutral consumer
 * contract.</p>
 */
public enum FieldPresence {
    PRESENT,
    ABSENT,
    UNKNOWN
}
