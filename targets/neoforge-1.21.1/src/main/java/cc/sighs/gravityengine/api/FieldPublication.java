package cc.sighs.gravityengine.api;

import cc.sighs.gravityengine.gravity.field.GravityFieldRuntime;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import cc.sighs.gravityengine.gravity.model.GravityFieldId;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handle for one successful publication instance, or a stale rejection.
 * Revision orders submissions; release checks an independent, non-reusable identity.
 * Replacing or removing/recreating the same ID and revision never transfers ownership.
 * Level and runtime references are weak; the identity retains no consumer resources.
 * Closing after unload is harmless and never creates a runtime.
 */
public final class FieldPublication implements AutoCloseable {
    public enum Status {
        /** The submitted revision replaced or created the registration. */
        ACCEPTED,
        /** A registration with an equal or higher revision already existed. */
        REJECTED_STALE
    }

    private final WeakReference<Level> level;
    private final WeakReference<GravityFieldRuntime> runtime;
    private final cc.sighs.gravityengine.gravity.field.GravityFieldRegistry.PublicationToken token;
    private final GravityFieldId id;
    private final long revision;
    private final Status status;
    private final AtomicBoolean closed;

    FieldPublication(
            Level level,
            GravityFieldId id,
            long revision,
            GravityFieldRuntime.PublicationReceipt receipt
    ) {
        this.level = new WeakReference<>(
                Objects.requireNonNull(level, "level")
        );
        this.runtime = new WeakReference<>(receipt.runtime());
        this.token = receipt.token();
        this.id = Objects.requireNonNull(id, "id");
        this.revision = revision;
        this.status = token != null ? Status.ACCEPTED : Status.REJECTED_STALE;
        this.closed = new AtomicBoolean(status != Status.ACCEPTED);
    }

    public Status status() {
        return status;
    }

    public boolean accepted() {
        return status == Status.ACCEPTED;
    }

    public ResourceLocation fieldId() {
        return MinecraftMathAdapter.toResourceLocation(id);
    }

    /** The submitted definition revision, whether or not it was accepted. */
    public long revision() {
        return revision;
    }

    /** True after local release completes, or for a rejected submission.
     * This is not a live registration query: replacement does not close old handles. */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Releases this publication if still owned, then completes local closure.
     * A live session requires its owning Level thread and a permitted mutation phase
     * (normal operation or onOpen, outside evaluation/publication callbacks).
     * A rejected thread/phase check leaves the handle open for a legal retry.
     * Repeated successful closure and closure after unload are harmless.
     */
    @Override
    public synchronized void close() {
        if (closed.get()) return;
        Level bound = level.get();
        GravityFieldRuntime session = runtime.get();
        if (bound != null && session != null) session.release(id, token);
        closed.set(true);
    }
}
