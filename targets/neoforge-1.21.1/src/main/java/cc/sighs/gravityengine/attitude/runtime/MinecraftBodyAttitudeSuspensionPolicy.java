package cc.sighs.gravityengine.attitude.runtime;

import java.util.Objects;

/**
 * Minecraft 1.21.1 presentation ownership rules for
 * body-attitude suspension.
 */
public final class MinecraftBodyAttitudeSuspensionPolicy {
    private MinecraftBodyAttitudeSuspensionPolicy() {}

    public static BodyAttitudeSuspensionLookPolicy lookPolicy(
            BodyAttitudeSuspensionReason reason
    ) {
        Objects.requireNonNull(reason, "reason");

        return reason
                == BodyAttitudeSuspensionReason.SLEEPING
                ? BodyAttitudeSuspensionLookPolicy.VANILLA_FORCED_CAMERA
                : BodyAttitudeSuspensionLookPolicy.PRESERVE_WORLD_LOOK;
    }
}
