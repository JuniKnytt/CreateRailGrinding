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

public final class JumpChargeOverlay {

    static final ResourceLocation OVERLAY_ID =
        ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "jump_charge_bar");

    private static final int BAR_PIPES = 30;

    private static final int FROM_COLOR = 0xFFC244;
    private static final int TO_COLOR   = 0x529915;

    private static final int EMPTY_COLOR = 0x544D45;

    private static final float PROMPT_CHASE_SPEED = 0.5f;

    private static final int PROMPT_WIDTH_PADDING = 17;

    private static final LerpedFloat displayedPromptSize = LerpedFloat.linear();

    private JumpChargeOverlay() {}

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

    static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        if (mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.SPECTATOR) return;

        float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(false);
        int promptSize = (int) displayedPromptSize.getValue(partialTicks);
        if (promptSize <= 1) return;

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(graphics.guiWidth() / 2f - 91f, graphics.guiHeight() - 29f, 0f);

        pose.pushPose();
        pose.translate(promptSize / -2f + 91f, -27f, 0f);
        AllGuiTextures.TRAIN_PROMPT_L.render(graphics, -3, 0);
        AllGuiTextures.TRAIN_PROMPT_R.render(graphics, promptSize, 0);
        graphics.blit(AllGuiTextures.TRAIN_PROMPT.location, 0, 0, 0,
            AllGuiTextures.TRAIN_PROMPT.getStartX() + (128 - promptSize / 2f),
            AllGuiTextures.TRAIN_PROMPT.getStartY(),
            promptSize, AllGuiTextures.TRAIN_PROMPT.getHeight(), 256, 256);
        pose.popPose();

        Component bar = buildBarComponent(getChargeRatio());
        Font font = mc.font;
        if (font.width(bar) < promptSize - 10) {
            pose.pushPose();

            pose.translate(font.width(bar) / -2f + 82f, -27f, 100f);

            graphics.drawString(font, bar, 9, 4, EMPTY_COLOR, false);
            pose.popPose();
        }

        pose.popPose();
    }
}
