package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.attitude.presentation.BodyAttitudeRenderSnapshot;

import cc.sighs.gravityengine.attitude.AttitudeSpaceTransform;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Render-call scoped context for one {@code LivingEntityRenderer.render}
 * invocation.
 *
 * <p>The scope stores the single immutable gravity render snapshot and the
 * single body-attitude snapshot sampled by that render, including explicit
 * pending input. {@link Scope#modelLook} lazily derives the model-local
 * head yaw/pitch from those cached values exactly once, so {@code setupAnim},
 * armor/feature layers and third-party layers all consume the same angles and
 * no consumer re-samples gravity mid-render.</p>
 *
 * <p>The stack is a {@link ThreadLocal} {@code ArrayDeque} because entity
 * rendering can nest; callers must pop in a {@code finally} so exceptions never
 * poison later renders.</p>
 */
public final class LivingAttitudeRenderContext {
    private static final ThreadLocal<ArrayDeque<Scope>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private LivingAttitudeRenderContext() {}

    /** Pushes a render scope; pop it in {@code finally} via {@link #pop()}. */
    public static Scope push() {
        return pushScope(new Scope());
    }

    static Scope pushScope(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        STACK.get().push(scope);
        return scope;
    }

    public static void pop() {
        ArrayDeque<Scope> stack = STACK.get();
        if (stack.isEmpty()) {
            throw new IllegalStateException(
                    "LivingAttitudeRenderContext.pop without push");
        }
        stack.pop();
        if (stack.isEmpty()) {
            STACK.remove();
        }
    }

    @Nullable
    public static Scope current() {
        return STACK.get().peek();
    }

    /** Mutable render-scoped holder; never stored globally after render. */
    public static final class Scope {
        @Nullable private ClientGravityFrameSampler.RenderSnapshot
                gravitySnapshot;
        @Nullable private BodyAttitudeRenderSnapshot attitude;
        @Nullable private AttitudeSpaceTransform.LocalLookAngles modelLook;
        private boolean modelLookComputed;
        private float maxHeadDegrees;

        Scope() {}

        /**
         * Records the single immutable gravity snapshot, the (possibly null)
         * body-attitude snapshot for this render.
         * Gravity is never sampled again inside {@link #modelLook}.
         */
        public void recordPresentation(
                ClientGravityFrameSampler.RenderSnapshot gravitySnapshot,
                @Nullable BodyAttitudeRenderSnapshot attitude,
                float maxHeadDegrees
        ) {
            this.gravitySnapshot = Objects.requireNonNull(
                    gravitySnapshot, "gravitySnapshot");
            this.attitude = attitude;
            this.maxHeadDegrees = maxHeadDegrees;
            this.modelLook = null;
            this.modelLookComputed = false;
        }

        @Nullable
        public BodyAttitudeRenderSnapshot attitude() {
            return this.attitude;
        }

        @Nullable
        ClientGravityFrameSampler.RenderSnapshot gravitySnapshot() {
            return this.gravitySnapshot;
        }

        /**
         * Model-local head joints for the recorded attitude, or the vanilla
         * fallback when no attitude is active. With an attitude snapshot the
         * head joints are the anatomically bounded projection of semantic look.
         * The camera remains free beyond those limits. Renderer bodyYaw/netHeadYaw
         * are not source-compatible inputs in this reference space.
         */
        public AttitudeSpaceTransform.LocalLookAngles modelLook(
                float vanillaNetHeadYaw,
                float vanillaHeadPitch
        ) {
            if (this.modelLookComputed) {
                return this.modelLook;
            }

            AttitudeSpaceTransform.LocalLookAngles fallback =
                    new AttitudeSpaceTransform.LocalLookAngles(
                            vanillaNetHeadYaw,
                            vanillaHeadPitch
                    );

            if (this.attitude == null) {
                this.modelLook = fallback;
                this.modelLookComputed = true;
                return fallback;
            }

            // Only the anatomical model is limited. Gameplay aim can wait beyond
            // this joint against the displayed actor frame.
            this.modelLook = ControllerHeadPresentation.modelLook(this.attitude);
            this.modelLookComputed = true;
            return this.modelLook;
        }
    }
}
