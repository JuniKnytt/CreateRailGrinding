package net.juniknytt.createrailgrinding.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.juniknytt.createrailgrinding.Config;
import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.network.RailGrindDebugSyncPayload;
import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Debug-only: draws two oriented wireframe boxes for the local player's grind, both rotated to
 * face the travel-direction tangent. Toggled by Config.DEBUG_MODE.
 *  - red box at the spline centerline (raw rail point — what the rail topology says)
 *  - blue box at the player snap target (centerline + lateral rail-bar offset + Y hover offset
 *    — what RailGrindHandler.applyTickMotion() is actually driving the player toward).
 *
 * <p>Data sources in priority order:
 *  <ol>
 *      <li>{@link RailGrindDebugSyncCache} — server is broadcasting debug snapshots (server
 *          config {@code syncDebugToClients=true}); needed on dedicated server.</li>
 *      <li>{@link RailGrindHandler#getGrindFrame} — only works on integrated server because
 *          the ACTIVE map lives in the same JVM as the client there.</li>
 *  </ol>
 */
@EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
public final class RailGrindDebugRenderer {
    // Half-extents come from RailGrindHandler so the rendered cube and applyTickMotion's
    // realignment threshold can't drift apart — the realignment check uses the same numbers
    // to decide whether the player has left the cube.
    private static final double HALF_W = RailGrindHandler.SNAP_BOX_HALF_W;
    private static final double HALF_H = RailGrindHandler.SNAP_BOX_HALF_H;
    private static final double HALF_L = RailGrindHandler.SNAP_BOX_HALF_L;

    private RailGrindDebugRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!Config.DEBUG_MODE.get()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        Vec3 origin;
        Vec3 tangent;
        Vec3 snap;
        RailGrindDebugSyncPayload synced = RailGrindDebugSyncCache.getFresh();
        if (synced != null) {
            if (!synced.hasGrindState()) return;
            origin = new Vec3(synced.originX(), synced.originY(), synced.originZ());
            tangent = new Vec3(synced.tangentX(), synced.tangentY(), synced.tangentZ());
            snap = new Vec3(synced.snapX(), synced.snapY(), synced.snapZ());
        } else {
            RailGrindHandler.GrindFrame frame = RailGrindHandler.getGrindFrame(mc.player);
            if (frame == null) return;
            origin = frame.origin();
            tangent = frame.tangent();
            snap = frame.snapTarget();
        }
        Vec3 cam = event.getCamera().getPosition();

        // Yaw + pitch derived so local +Z aligns with the tangent.
        // yaw   = atan2(dx, dz)       — Axis.YP rotates +Z toward +X via positive sin θ
        // pitch = asin(-dy / |t|)     — Axis.XP rotates +Z toward -Y via positive sin φ; we want +Y when dy>0, so negate
        double len = tangent.length();
        boolean rotate = len > 1e-6;
        float yaw = rotate ? (float) Math.atan2(tangent.x, tangent.z) : 0f;
        float pitch = rotate ? (float) Math.asin(-tangent.y / len) : 0f;

        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        drawBox(pose, lines, origin, cam, rotate, yaw, pitch, 1.0f, 0.2f, 0.2f);
        drawBox(pose, lines, snap,   cam, rotate, yaw, pitch, 0.2f, 0.4f, 1.0f);

        buffers.endBatch(RenderType.lines());
    }

    private static void drawBox(PoseStack pose, VertexConsumer lines, Vec3 at, Vec3 cam,
                                boolean rotate, float yaw, float pitch,
                                float r, float g, float b) {
        pose.pushPose();
        pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
        if (rotate) {
            pose.mulPose(Axis.YP.rotation(yaw));
            pose.mulPose(Axis.XP.rotation(pitch));
        }
        LevelRenderer.renderLineBox(pose, lines,
            -HALF_W, -HALF_H, -HALF_L, HALF_W, HALF_H, HALF_L,
            r, g, b, 1.0f);
        pose.popPose();
    }
}
