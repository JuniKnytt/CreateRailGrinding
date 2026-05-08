package net.juniknytt.createrailgrinding.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.animation.LerpedFloat.Chaser;
import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Replicates Create's "hold space to approach station" pipe-bar prompt, repurposed for the
 * rail-grind dismount jump-charge. Renders only while the local player is grinding and a
 * charge is in progress (jump pressed during the grind, not yet released). The bar's filled
 * segment grows from 0 to 30 pipes over the configured charge window; the filled-portion
 * color interpolates from the same gold the Create bar starts at to the same green it ends
 * at, and the empty-portion color matches Create's dim-gray empty segments.
 *
 * <p>The frame textures (TRAIN_PROMPT_L / TRAIN_PROMPT / TRAIN_PROMPT_R), prompt-size lerp
 * speed, vertical offset, and centered anchoring are all lifted from Create's TrainHUD prompt
 * rendering so the visual is indistinguishable from the conductor's approach-station prompt.
 *
 * <p>State is read from {@link ClientInputHandler#isCharging()} /
 * {@link ClientInputHandler#getChargeHeldTicks()} — purely client-side, no server sync needed
 * during the charge. The released duration becomes a charge multiplier on the speed-based
 * launch impulse in {@link RailGrindHandler#stopWithLaunch}.
 */
public final class JumpChargeOverlay {
    private static final ResourceLocation OVERLAY_ID =
        ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "jump_charge_bar");

    // Same 30-segment bar Create uses in CarriageContraptionEntity#control's spaceDown branch.
    private static final int BAR_PIPES = 30;
    // Filled-segment gradient endpoints — same RGB constants as Create's approach-station bar
    // (fromColor 0xFFC244, toColor 0x529915). Interpolated by chargeRatio: gold at ratio 0,
    // green at ratio 1, smoothly through olive in between.
    private static final int FROM_COLOR = 0xFFC244;
    private static final int TO_COLOR   = 0x529915;
    // Empty-segment dim gray — same hex Create uses for unfilled pipes and prompt text.
    private static final int EMPTY_COLOR = 0x544D45;
    // Same chase speed (50% per tick) and exp easing TrainHUD applies to displayedPromptSize,
    // so the frame slide-in/slide-out feels identical.
    private static final float PROMPT_CHASE_SPEED = 0.5f;
    // Width padding TrainHUD adds around the prompt text when computing its target frame
    // size (mc.font.width(currentPrompt) + 17). Reproduced verbatim so the frame shape
    // matches Create's at every fill state.
    private static final int PROMPT_WIDTH_PADDING = 17;

    private static final LerpedFloat displayedPromptSize = LerpedFloat.linear();

    private JumpChargeOverlay() {}

    @EventBusSubscriber(modid = RailGrind.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBusEvents {
        @SubscribeEvent
        public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
            // Above EXPERIENCE_BAR so the bar sits in the same vertical slot Create's prompt
            // uses (above the hotbar) without fighting the speedometer overlay's anchor.
            event.registerAbove(VanillaGuiLayers.EXPERIENCE_BAR, OVERLAY_ID, JumpChargeOverlay::render);
        }
    }

    @EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
    public static final class GameBusEvents {
        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            tickChaser();
        }
    }

    private static void tickChaser() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.font == null) return;
        int target = 0;
        if (isVisible()) {
            // Width is independent of fill — all 30 pipes are present at every charge state,
            // only their colors change. So the prompt size is a constant for the duration of a
            // charge, just chased smoothly in/out at the start/end. Computing it from the
            // component itself (instead of hardcoding) keeps it correct if BAR_PIPES changes.
            target = mc.font.width(buildBarComponent(0.0)) + PROMPT_WIDTH_PADDING;
        }
        displayedPromptSize.chase(target, PROMPT_CHASE_SPEED, Chaser.EXP);
        displayedPromptSize.tickChaser();
    }

    private static boolean isVisible() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null
            && BalancingPoseTracker.isBalancing(player)
            && ClientInputHandler.isCharging();
    }

    private static double getChargeRatio() {
        int held = ClientInputHandler.getChargeHeldTicks();
        if (held < 0) return 0.0;
        return RailGrindHandler.computeChargeRatio(held);
    }

    private static MutableComponent buildBarComponent(double chargeRatio) {
        double clamped = Mth.clamp(chargeRatio, 0.0, 1.0);
        int filled = (int) (clamped * BAR_PIPES);
        int mixedColor = mixColors(FROM_COLOR, TO_COLOR, (float) clamped);
        MutableComponent filledComp = Component.literal("|".repeat(filled))
            .withStyle(s -> s.withColor(mixedColor));
        MutableComponent emptyComp = Component.literal("|".repeat(BAR_PIPES - filled))
            .withStyle(s -> s.withColor(EMPTY_COLOR));
        return filledComp.append(emptyComp);
    }

    private static int mixColors(int from, int to, float t) {
        t = Mth.clamp(t, 0f, 1f);
        int fr = (from >> 16) & 0xFF;
        int fg = (from >> 8)  & 0xFF;
        int fb = from         & 0xFF;
        int tr = (to >> 16)   & 0xFF;
        int tg = (to >> 8)    & 0xFF;
        int tb = to           & 0xFF;
        int r = Math.round(fr + (tr - fr) * t);
        int g = Math.round(fg + (tg - fg) * t);
        int b = Math.round(fb + (tb - fb) * t);
        return (r << 16) | (g << 8) | b;
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        if (mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.SPECTATOR) return;

        float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(false);
        int promptSize = (int) displayedPromptSize.getValue(partialTicks);
        if (promptSize <= 1) return;

        // Anchor mirrors TrainHUD: bottom-center of the screen, the same spot the train
        // controls HUD would sit. Frame and text are translated relative to it.
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(graphics.guiWidth() / 2f - 91f, graphics.guiHeight() - 29f, 0f);

        // Frame (left cap, right cap, stretched center). Identical sequence to TrainHUD's
        // prompt frame draw — same offsets, same texture rectangle slice.
        pose.pushPose();
        pose.translate(promptSize / -2f + 91f, -27f, 0f);
        AllGuiTextures.TRAIN_PROMPT_L.render(graphics, -3, 0);
        AllGuiTextures.TRAIN_PROMPT_R.render(graphics, promptSize, 0);
        graphics.blit(AllGuiTextures.TRAIN_PROMPT.location, 0, 0, 0,
            AllGuiTextures.TRAIN_PROMPT.getStartX() + (128 - promptSize / 2f),
            AllGuiTextures.TRAIN_PROMPT.getStartY(),
            promptSize, AllGuiTextures.TRAIN_PROMPT.getHeight(), 256, 256);
        pose.popPose();

        // Pipe-bar text. Built fresh each frame so the live charge ratio drives the fill.
        Component bar = buildBarComponent(getChargeRatio());
        Font font = mc.font;
        if (font.width(bar) < promptSize - 10) {
            pose.pushPose();
            // Same translation TrainHUD uses for the prompt text — centered horizontally
            // within the frame, vertically aligned to the same -27 + 4 = -23 baseline. The
            // z=100 lifts the text above the frame in the depth buffer.
            pose.translate(font.width(bar) / -2f + 82f, -27f, 100f);
            // No-shadow draw matches TrainHUD's currentPromptShadow=false branch (the
            // approach-station bar uses the no-shadow path so the per-character colors aren't
            // washed out by a global drop shadow).
            graphics.drawString(font, bar, 9, 4, EMPTY_COLOR, false);
            pose.popPose();
        }

        pose.popPose();
    }
}
