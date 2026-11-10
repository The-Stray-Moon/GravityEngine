package cc.sighs.gravityengine.gravity.integration.compat.create;

import cc.sighs.gravityengine.gravity.collision.GravitySupportContact;
import cc.sighs.gravityengine.gravity.integration.BlockContactResolver;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import java.util.Optional;

/** Optional boundary; loading ordinary GE never resolves Create classes. */
public final class CreateCompatibility {
    private static final boolean AVAILABLE = CreateCompatibility.class.getClassLoader()
            .getResource("com/simibubi/create/Create.class") != null;
    private CreateCompatibility() {}
    public static void captureEnvironment(cc.sighs.gravityengine.gravity.integration.MovementEnvironmentCapture.Builder result) {
        if (AVAILABLE) CreateRigidCollisionProvider.captureEnvironment(result);
    }
    public static void joined(Entity entity) { if (AVAILABLE) CreateRigidCollisionProvider.joined(entity); }
    public static void left(Entity entity) { if (AVAILABLE) CreateRigidCollisionProvider.left(entity); }
    public static void unload(Level level) { if (AVAILABLE) CreateRigidCollisionProvider.unload(level); }
    public static void invalidateBounds(Level level) { if(AVAILABLE) CreateRigidCollisionProvider.invalidateBounds(level); }
    public static boolean publishes(Object contraption) {
        return AVAILABLE && CreateRigidCollisionProvider.publishes(contraption);
    }
    public static Optional<BlockContactResolver.Resolved> resolve(Entity actor, GravitySupportContact contact) {
        return AVAILABLE ? CreateRigidCollisionProvider.resolveContact(actor, contact) : Optional.empty();
    }
}
