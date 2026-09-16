package cc.sighs.gravityengine.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Client-only held roll bindings.
 *
 * <p>This class owns only key registration and current held roll-axis
 * queries.  Roll is sampled into the per-tick movement snapshot at the
 * {@code LocalPlayer.aiStep} semantic capture boundary; there is no separate
 * roll publish/lifecycle state here and no {@code ClientTickEvent}
 * dependency.</p>
 */
public final class ClientBodyAttitudeKeys {
    public static final String CATEGORY = "key.categories.gravityengine.attitude";
    /*
     * Roll defaults must not occupy vanilla mouse attack/use. Z / C are the
     * module defaults; there is deliberately no "cancel attack while flying"
     * hack that would let mouse buttons double as roll bindings.
     */
    public static final KeyMapping ROLL_COUNTER_CLOCKWISE = new KeyMapping(
            "key.gravityengine.roll_counter_clockwise",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, CATEGORY);
    public static final KeyMapping ROLL_CLOCKWISE = new KeyMapping(
            "key.gravityengine.roll_clockwise",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY);

    private ClientBodyAttitudeKeys() {}

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(ROLL_COUNTER_CLOCKWISE);
        event.register(ROLL_CLOCKWISE);
    }

    /**
     * Whether player controls may be captured at all: the window has focus,
     * no GUI screen is open and a connection is present.
     */
    public static boolean acceptsInput() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null
                || minecraft.getConnection() == null) {
            return false;
        }
        return minecraft.isWindowActive() && minecraft.screen == null;
    }

    /** Pure held roll-axis evaluation under the supplied input gate. */
    public static int rollAxis(boolean acceptsInput) {
        if (!acceptsInput) return 0;
        return (ROLL_CLOCKWISE.isDown() ? 1 : 0)
                - (ROLL_COUNTER_CLOCKWISE.isDown() ? 1 : 0);
    }

    /**
     * Current held roll axis respecting the input-focus/screen-open policy.
     * Counter-clockwise is -1 and clockwise is +1 when viewed from behind
     * the controller looking along canonical controller forward (+Z).
     */
    public static int currentRollAxis() {
        return rollAxis(acceptsInput());
    }
}
