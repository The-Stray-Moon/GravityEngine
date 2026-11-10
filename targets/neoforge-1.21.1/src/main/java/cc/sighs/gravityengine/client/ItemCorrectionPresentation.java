package cc.sighs.gravityengine.client;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/** Client-thread, entity-lifetime render history. Never writes physical state.
 * Small network corrections settle over five ticks. Large relocations and
 * grounded/removed entities discard history. No persistence or wire state. */
public final class ItemCorrectionPresentation {
    private static final Map<ItemEntity, History> HISTORY = new WeakHashMap<>();
    private static final int SETTLE_TICKS = 5;
    private ItemCorrectionPresentation() {}

    public static void corrected(Entity entity, Vec3 before) {
        if (!(entity instanceof ItemEntity item) || !item.level().isClientSide()) return;
        Vec3 correction = before.subtract(item.position());
        if (item.isRemoved() || item.onGround() || correction.lengthSqr() > 16) {
            HISTORY.remove(item);
            return;
        }
        if (correction.lengthSqr() == 0) return;
        History h = HISTORY.computeIfAbsent(item, ignored -> new History(item.tickCount));
        h.advance(item.tickCount);
        // lerpTo changes current position only; xOld/yOld/zOld remain native
        // interpolation history. Compensate that same endpoint, not both.
        h.current = h.current.add(correction);
        h.remaining = SETTLE_TICKS;
        if (h.current.lengthSqr() > 16) HISTORY.remove(item);
    }

    public static Vec3 offset(Entity entity, float partialTick) {
        if (!(entity instanceof ItemEntity item)) return Vec3.ZERO;
        History h = HISTORY.get(item);
        if (h == null) return Vec3.ZERO;
        if (item.isRemoved() || item.onGround()) {
            HISTORY.remove(item);
            return Vec3.ZERO;
        }
        h.advance(item.tickCount);
        if (h.previous.lengthSqr() == 0 && h.current.lengthSqr() == 0) {
            HISTORY.remove(item);
            return Vec3.ZERO;
        }
        return h.previous.lerp(h.current, partialTick);
    }

    private static final class History {
        private int tick;
        private int remaining;
        private Vec3 previous = Vec3.ZERO;
        private Vec3 current = Vec3.ZERO;
        private History(int tick) { this.tick = tick; }
        private void advance(int now) {
            if (now - tick > SETTLE_TICKS + 1 || now < tick) {
                previous = current = Vec3.ZERO;
                remaining = 0;
            } else {
                for (int i = tick; i < now; i++) {
                    previous = current;
                    current = remaining > 1 ? current.scale((double) (remaining - 1) / remaining) : Vec3.ZERO;
                    if (remaining > 0) remaining--;
                }
            }
            tick = now;
        }
    }
}
