package net.juniknytt.createrailgrinding.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.animation.LerpedFloat.Chaser;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.placement.PlacementClient;
import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Renders a speedometer above the hotbar while the local player is grinding, using Create's
 * train-HUD textures so it visually matches the controls block UI. While the speedometer is
 * active the vanilla XP bar is suppressed (mirroring how mounting a horse swaps in the jump
 * meter), so the two never overlap.
 *
 * <p>Behavior is mirrored from Create's {@code TrainHUD}: the bar fill chases an 18-segment
 * snapped target through a {@link LerpedFloat} so it fills/drains smoothly rather than popping,
 * and a directional arrow (drawn via {@link PlacementClient#textured}) shows the travel
 * direction relative to the camera, snapped to 22.5° steps to match the texture's pre-baked
 * rotation frames. The arrow tracks <em>actual</em> direction of motion — input-based
 * adjustments (turn/reverse keys, throttle pointer) are intentionally omitted for now.
 *
 * <p>Speed is read from {@code player.getDeltaMovement().length()} rather than synced from the
 * server-side {@link RailGrindHandler}: {@code applyTickMotion} sets the player's velocity
 * with {@code hurtMarked = true} every tick, which forces a velocity packet to the client,
 * so the local player's deltaMovement already tracks the grind speed without an extra packet.
 */
public final class GrindSpeedometerOverlay {
    private static final ResourceLocation OVERLAY_ID =
        ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "grind_speedometer");

    private static final int BAR_SEGMENTS = 18;        // Create's TrainHUD divides the bar into 18 stops
    private static final float CHASE_SPEED = 0.5f;     // matches TrainHUD.tick — 50% toward target per tick
    private static final float ARROW_SNAP_DEG = 22.5f; // PLACEMENT_INDICATOR_SHEET has 16 frames over 360°

    /** Bar fill in [0, 1], chased toward an 18-segment snapped target. */
    private static final LerpedFloat displayedSpeed = LerpedFloat.linear();
    /** Direction-of-travel yaw in degrees; angular variant handles 360° wraparound. */
    private static final LerpedFloat displayedTravelYaw = LerpedFloat.angular();

    private GrindSpeedometerOverlay() {}

    @EventBusSubscriber(modid = RailGrind.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBusEvents {
        @SubscribeEvent
        public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
            // Suppress the XP bar (and the horse jump meter, defensively) while grinding so the
            // speedometer sits in their place instead of overlapping.
            event.wrapLayer(VanillaGuiLayers.EXPERIENCE_BAR, GrindSpeedometerOverlay::wrapHidden);
            event.wrapLayer(VanillaGuiLayers.JUMP_METER,     GrindSpeedometerOverlay::wrapHidden);
            event.registerAbove(VanillaGuiLayers.EXPERIENCE_BAR, OVERLAY_ID, GrindSpeedometerOverlay::render);
        }
    }

    @EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
    public static final class GameBusEvents {
        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            tickChasers();
        }
    }

    private static void tickChasers() {
        LocalPlayer player = Minecraft.getInstance().player;
        boolean grinding = player != null && BalancingPoseTracker.isBalancing(player);

        if (grinding) {
            double speedMs = player.getDeltaMovement().length() * 20.0;
            double topSpeedMs = RailGrindHandler.TOP_SPEED * 20.0;
            // +0.05 padding (lifted from Create's TrainHUD) so a tiny non-zero speed still lights a segment.
            double value = Mth.clamp(speedMs / topSpeedMs + 0.05, 0.0, 1.0);
            double snapped = (int) (value * BAR_SEGMENTS) / (double) BAR_SEGMENTS;
            displayedSpeed.chase(snapped, CHASE_SPEED, Chaser.EXP);

            Vec3 vel = player.getDeltaMovement();
            if (vel.x * vel.x + vel.z * vel.z > 1.0e-4) {
                float yaw = (float) Math.toDegrees(Math.atan2(-vel.x, vel.z));
                displayedTravelYaw.chase(yaw, CHASE_SPEED, Chaser.EXP);
            }
        } else {
            // Drain the bar between grinds so the next one starts from empty rather than mid-fill.
            displayedSpeed.chase(0.0, CHASE_SPEED, Chaser.EXP);
        }
        displayedSpeed.tickChaser();
        displayedTravelYaw.tickChaser();
    }

    private static LayeredDraw.Layer wrapHidden(LayeredDraw.Layer original) {
        return (graphics, deltaTracker) -> {
            if (isLocalPlayerGrinding()) return;
            original.render(graphics, deltaTracker);
        };
    }

    private static boolean isLocalPlayerGrinding() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && BalancingPoseTracker.isBalancing(player);
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        if (mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.SPECTATOR) return;
        LocalPlayer player = mc.player;
        if (player == null || !BalancingPoseTracker.isBalancing(player)) return;
        Entity camera = mc.getCameraEntity();
        if (camera == null) return;

        float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(false);
        float fill = displayedSpeed.getValue(partialTicks);

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(graphics.guiWidth() / 2 - 91, graphics.guiHeight() - 29, 0);

        AllGuiTextures.TRAIN_HUD_FRAME.render(graphics, -2, 1);
        AllGuiTextures.TRAIN_HUD_SPEED_BG.render(graphics, 0, 0);

        int barW = (int) (AllGuiTextures.TRAIN_HUD_SPEED.getWidth() * fill);
        int barH = AllGuiTextures.TRAIN_HUD_SPEED.getHeight();
        graphics.blit(AllGuiTextures.TRAIN_HUD_SPEED.location, 0, 0, 0,
            AllGuiTextures.TRAIN_HUD_SPEED.getStartX(),
            AllGuiTextures.TRAIN_HUD_SPEED.getStartY(),
            barW, barH, 256, 256);

        AllGuiTextures.TRAIN_HUD_DIRECTION.render(graphics, 77, -20);

        float travelYaw = displayedTravelYaw.getValue(partialTicks);
        float diff = AngleHelper.getShortestAngleDiff(camera.getYRot(), travelYaw);
        // Mirror TrainHUD: when the camera roughly faces the direction of travel, lock the arrow
        // to "forward" so it doesn't wiggle on small head movements. The lock is applied BEFORE
        // adding the steer offset so a left/right press always tilts a clean ±45° from forward
        // rather than off some near-zero residual.
        if (Math.abs(diff) < 60f) diff = 0f;
        // Steer overlay (mirrors Create's TrainHUD): each strafe key adds ±45° to the arrow,
        // independent of whether it's actually changing the player's path. So the arrow shows
        // *intent* — which exit the player is asking for at the next intersection — exactly the
        // way the train HUD shows steering intent on a player-controlled contraption.
        // (Create's HUD additionally flips by 90° for reversed seats and inverts the sign on
        // backward-key — neither applies here: the player has no seat orientation and can't
        // grind backward, so the bare ±45° offset is all we need.)
        int turnOffset = 0;
        if (mc.options.keyLeft.isDown())  turnOffset -= 45;
        if (mc.options.keyRight.isDown()) turnOffset += 45;
        float angle = diff + turnOffset;
        float snappedAngle = (ARROW_SNAP_DEG * Math.round(angle / ARROW_SNAP_DEG)) % 360f;
        pose.translate(91, -9, 0);
        pose.scale(0.925f, 0.925f, 1);
        PlacementClient.textured(pose, 0, 0, 1, snappedAngle);

        pose.popPose();
    }
}
