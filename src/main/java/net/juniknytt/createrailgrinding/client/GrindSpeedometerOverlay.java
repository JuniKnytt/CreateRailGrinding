package net.juniknytt.createrailgrinding.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.animation.LerpedFloat.Chaser;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.placement.PlacementClient;
import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
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

public final class GrindSpeedometerOverlay {
    private static final ResourceLocation OVERLAY_ID =
        ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "grind_speedometer");

    private static final int BAR_SEGMENTS = 18;
    private static final float BAR_CHASE_SPEED = 0.3f;
    private static final float ARROW_CHASE_SPEED = 0.4f;
    private static final float ARROW_SNAP_DEG = 22.5f;

    private static final LerpedFloat displayedSpeed = LerpedFloat.linear();

    private static final LerpedFloat displayedTravelYaw = LerpedFloat.angular();

    private GrindSpeedometerOverlay() {}

    @EventBusSubscriber(modid = RailGrind.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBusEvents {
        @SubscribeEvent
        public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {

            event.wrapLayer(VanillaGuiLayers.EXPERIENCE_BAR, GrindSpeedometerOverlay::wrapHidden);
            event.wrapLayer(VanillaGuiLayers.JUMP_METER,     GrindSpeedometerOverlay::wrapHidden);

            event.registerAbove(VanillaGuiLayers.EXPERIENCE_BAR, JumpChargeOverlay.OVERLAY_ID, JumpChargeOverlay::render);
            event.registerAbove(JumpChargeOverlay.OVERLAY_ID, OVERLAY_ID, GrindSpeedometerOverlay::render);
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
            double topSpeedMs = RailGrindHandler.topSpeed() * 20.0;

            double value = Mth.clamp(speedMs / topSpeedMs + 0.05, 0.0, 1.0);
            double snapped = (int) (value * BAR_SEGMENTS) / (double) BAR_SEGMENTS;
            displayedSpeed.chase(snapped, BAR_CHASE_SPEED, Chaser.EXP);

            Vec3 vel = player.getDeltaMovement();
            if (vel.x * vel.x + vel.z * vel.z > 1.0e-4) {
                float yaw = (float) Math.toDegrees(Math.atan2(-vel.x, vel.z));
                displayedTravelYaw.chase(yaw, ARROW_CHASE_SPEED, Chaser.EXP);
            }
        } else {

            displayedSpeed.chase(0.0, BAR_CHASE_SPEED, Chaser.EXP);
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

        if (Math.abs(diff) < 60f) diff = 0f;

        int turnOffset = 45 * ClientInputHandler.getSteerInput();
        float angle = diff + turnOffset;
        float snappedAngle = (ARROW_SNAP_DEG * Math.round(angle / ARROW_SNAP_DEG)) % 360f;
        pose.translate(91, -9, 0);
        pose.scale(0.925f, 0.925f, 1);
        PlacementClient.textured(pose, 0, 0, 1, snappedAngle);

        pose.popPose();
    }
}
