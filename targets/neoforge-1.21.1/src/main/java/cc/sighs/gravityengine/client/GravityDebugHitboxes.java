package cc.sighs.gravityengine.client;

import cc.sighs.gravityengine.ClientConfig;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CharacterCapsule;
import cc.sighs.gravityengine.gravity.kinematic.geometry.CollisionBody;
import cc.sighs.gravityengine.gravity.kinematic.geometry.OrientedBox;
import cc.sighs.gravityengine.gravity.minecraft.math.MinecraftMathAdapter;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

public final class GravityDebugHitboxes {
    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (!ClientConfig.gravityHitboxes) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;

        float partialTick = event.getPartialTick().getGameTimeDeltaTicks();
        PoseStack poseStack = event.getPoseStack();
        VertexConsumer vertices = minecraft.renderBuffers()
                .bufferSource()
                .getBuffer(RenderType.LINES);
        Vec3 cameraPosition = event.getCamera().getPosition();

        poseStack.pushPose();
        poseStack.translate(
                -cameraPosition.x,
                -cameraPosition.y,
                -cameraPosition.z
        );
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            var captured = GravityDebugEntityGeometry.capture(
                    entity,
                    partialTick
            );
            if (captured.isEmpty()) continue;

            GravityDebugEntityGeometry geometry = captured.get();
            renderAabb(
                    poseStack,
                    vertices,
                    geometry.entityBounds(),
                    ENTITY_AABB
            );

            CollisionBody physicalBody = geometry.physicalBody();
            if (physicalBody != null) {
                renderBody(poseStack, vertices, physicalBody, PHYSICAL);
            }

            CollisionBody presentationBody = geometry.presentationBody();
            if (presentationBody != null) {
                renderBody(poseStack, vertices, presentationBody, PRESENTATION);
            }

            if (physicalBody != null && presentationBody != null
                    && !physicalBody.center().equals(presentationBody.center())) {
                drawEdge(
                        poseStack,
                        vertices,
                        MinecraftMathAdapter.toMinecraft(
                                physicalBody.center()),
                        MinecraftMathAdapter.toMinecraft(
                                presentationBody.center()),
                        CENTER_DISTANCE
                );
            }
        }
        poseStack.popPose();
    }

    /** Draws the exact twelve edges of the supplied world-axis-aligned box. */
    private static void renderAabb(
            PoseStack poseStack,
            VertexConsumer vertices,
            AABB box,
            float[] color
    ) {
        Vec3 nnn = new Vec3(box.minX, box.minY, box.minZ);
        Vec3 pnn = new Vec3(box.maxX, box.minY, box.minZ);
        Vec3 npn = new Vec3(box.minX, box.maxY, box.minZ);
        Vec3 ppn = new Vec3(box.maxX, box.maxY, box.minZ);
        Vec3 nnp = new Vec3(box.minX, box.minY, box.maxZ);
        Vec3 pnp = new Vec3(box.maxX, box.minY, box.maxZ);
        Vec3 npp = new Vec3(box.minX, box.maxY, box.maxZ);
        Vec3 ppp = new Vec3(box.maxX, box.maxY, box.maxZ);

        drawEdge(poseStack, vertices, nnn, pnn, color);
        drawEdge(poseStack, vertices, npn, ppn, color);
        drawEdge(poseStack, vertices, nnp, pnp, color);
        drawEdge(poseStack, vertices, npp, ppp, color);
        drawEdge(poseStack, vertices, nnn, npn, color);
        drawEdge(poseStack, vertices, pnn, ppn, color);
        drawEdge(poseStack, vertices, nnp, npp, color);
        drawEdge(poseStack, vertices, pnp, ppp, color);
        drawEdge(poseStack, vertices, nnn, nnp, color);
        drawEdge(poseStack, vertices, pnn, pnp, color);
        drawEdge(poseStack, vertices, npn, npp, color);
        drawEdge(poseStack, vertices, ppn, ppp, color);
    }

    private static void renderBody(
            PoseStack poseStack,
            VertexConsumer vertices,
            CollisionBody body,
            float[] color
    ) {
        switch (body) {
            case CharacterCapsule capsule -> renderCapsule(
                    poseStack, vertices, capsule, color
            );
            case OrientedBox box -> renderObb(poseStack, vertices, box, color);
        }
    }

    private static void renderCapsule(
            PoseStack poseStack,
            VertexConsumer vertices,
            CharacterCapsule capsule,
            float[] color
    ) {
        final int segments = 16;
        Vec3 up = MinecraftMathAdapter.toMinecraft(capsule.axis());
        Vec3 seed = Math.abs(up.x) < 0.75D
                ? new Vec3(1.0D, 0.0D, 0.0D)
                : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right = up.cross(seed).normalize();
        Vec3 forward = up.cross(right).normalize();
        renderCircle(poseStack, vertices,
                MinecraftMathAdapter.toMinecraft(capsule.a()), right, forward,
                capsule.radius(), segments, color);
        renderCircle(poseStack, vertices,
                MinecraftMathAdapter.toMinecraft(capsule.b()), right, forward,
                capsule.radius(), segments, color);

        drawEdge(poseStack, vertices,
                MinecraftMathAdapter.toMinecraft(capsule.a())
                        .add(right.scale(capsule.radius())),
                MinecraftMathAdapter.toMinecraft(capsule.b())
                        .add(right.scale(capsule.radius())), color);
        drawEdge(poseStack, vertices,
                MinecraftMathAdapter.toMinecraft(capsule.a())
                        .subtract(right.scale(capsule.radius())),
                MinecraftMathAdapter.toMinecraft(capsule.b())
                        .subtract(right.scale(capsule.radius())), color);
        drawEdge(poseStack, vertices,
                MinecraftMathAdapter.toMinecraft(capsule.a())
                        .add(forward.scale(capsule.radius())),
                MinecraftMathAdapter.toMinecraft(capsule.b())
                        .add(forward.scale(capsule.radius())), color);
        drawEdge(poseStack, vertices,
                MinecraftMathAdapter.toMinecraft(capsule.a())
                        .subtract(forward.scale(capsule.radius())),
                MinecraftMathAdapter.toMinecraft(capsule.b())
                        .subtract(forward.scale(capsule.radius())), color);

        renderCapArc(poseStack, vertices,
                MinecraftMathAdapter.toMinecraft(capsule.a()), right, up.reverse(),
                capsule.radius(), segments / 2, color);
        renderCapArc(poseStack, vertices,
                MinecraftMathAdapter.toMinecraft(capsule.a()), forward, up.reverse(),
                capsule.radius(), segments / 2, color);
        renderCapArc(poseStack, vertices,
                MinecraftMathAdapter.toMinecraft(capsule.b()), right, up,
                capsule.radius(), segments / 2, color);
        renderCapArc(poseStack, vertices,
                MinecraftMathAdapter.toMinecraft(capsule.b()), forward, up,
                capsule.radius(), segments / 2, color);
    }

    private static void renderCircle(
            PoseStack poseStack,
            VertexConsumer vertices,
            Vec3 center,
            Vec3 firstAxis,
            Vec3 secondAxis,
            double radius,
            int segments,
            float[] color
    ) {
        Vec3 previous = center.add(firstAxis.scale(radius));
        for (int index = 1; index <= segments; index++) {
            double angle = Math.PI * 2.0D * index / segments;
            Vec3 current = center
                    .add(firstAxis.scale(Math.cos(angle) * radius))
                    .add(secondAxis.scale(Math.sin(angle) * radius));
            drawEdge(poseStack, vertices, previous, current, color);
            previous = current;
        }
    }

    private static void renderCapArc(
            PoseStack poseStack,
            VertexConsumer vertices,
            Vec3 center,
            Vec3 radialAxis,
            Vec3 capAxis,
            double radius,
            int segments,
            float[] color
    ) {
        Vec3 previous = center.add(radialAxis.scale(radius));
        for (int index = 1; index <= segments; index++) {
            double angle = Math.PI * index / segments;
            Vec3 current = center
                    .add(radialAxis.scale(Math.cos(angle) * radius))
                    .add(capAxis.scale(Math.sin(angle) * radius));
            drawEdge(poseStack, vertices, previous, current, color);
            previous = current;
        }
    }

    /** Draws the fallback primitive's exact edges rather than its broadphase AABB. */
    private static void renderObb(
            PoseStack poseStack,
            VertexConsumer vertices,
            OrientedBox box,
            float[] color
    ) {
        for (int y = -1; y <= 1; y += 2) {
            for (int z = -1; z <= 1; z += 2) {
                drawEdge(
                        poseStack,
                        vertices,
                        MinecraftMathAdapter.toMinecraft(
                                box.corner(-1, y, z)),
                        MinecraftMathAdapter.toMinecraft(
                                box.corner(1, y, z)),
                        color);
            }
        }
        for (int x = -1; x <= 1; x += 2) {
            for (int z = -1; z <= 1; z += 2) {
                drawEdge(
                        poseStack,
                        vertices,
                        MinecraftMathAdapter.toMinecraft(
                                box.corner(x, -1, z)),
                        MinecraftMathAdapter.toMinecraft(
                                box.corner(x, 1, z)),
                        color);
            }
        }
        for (int x = -1; x <= 1; x += 2) {
            for (int y = -1; y <= 1; y += 2) {
                drawEdge(
                        poseStack,
                        vertices,
                        MinecraftMathAdapter.toMinecraft(
                                box.corner(x, y, -1)),
                        MinecraftMathAdapter.toMinecraft(
                                box.corner(x, y, 1)),
                        color);
            }
        }
    }

    private static void drawEdge(
            PoseStack poseStack,
            VertexConsumer vertices,
            Vec3 start,
            Vec3 end,
            float[] color
    ) {
        Vec3 direction = end.subtract(start).normalize();
        PoseStack.Pose pose = poseStack.last();
        addLineVertex(vertices, pose, start, direction, color);
        addLineVertex(vertices, pose, end, direction, color);
    }

    private static void addLineVertex(
            VertexConsumer vertices,
            PoseStack.Pose pose,
            Vec3 point,
            Vec3 direction,
            float[] color
    ) {
        vertices.addVertex(pose, (float) point.x, (float) point.y, (float) point.z)
                .setColor(color[0], color[1], color[2], color[3])
                .setNormal(pose, (float) direction.x, (float) direction.y, (float) direction.z);
    }

    private static final float[] ENTITY_AABB = {0.80F, 0.80F, 0.80F, 0.55F};
    private static final float[] PRESENTATION = {1.0F, 1.0F, 0.0F, 0.5F};
    private static final float[] PHYSICAL = {0.4F, 1.0F, 1.0F, 0.9F};
    private static final float[] CENTER_DISTANCE = {1.0F, 0.2F, 0.2F, 0.8F};
}
