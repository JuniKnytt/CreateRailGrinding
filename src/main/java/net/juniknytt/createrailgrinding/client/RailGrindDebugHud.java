package net.juniknytt.createrailgrinding.client;

import net.juniknytt.createrailgrinding.Config;
import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.network.RailGrindDebugSyncPayload;
import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.Locale;

@EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
public final class RailGrindDebugHud {
    private static final int X_PAD = 4;
    private static final int Y_PAD = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int COLOR = 0xFFFFFFFF;

    private RailGrindDebugHud() {}

    private static String formatDriftMargin(double margin) {
        if (Double.isNaN(margin)) {
            return "distUntilMaxDrift: suppressed (in grace)";
        }
        return String.format(Locale.ROOT, "distUntilMaxDrift: %+.3f", margin);
    }

    private static String formatTicksSinceGraceEnded(int ticks) {
        if (ticks < 0) return "ticksSinceGraceEnded: in grace";
        return String.format(Locale.ROOT, "ticksSinceGraceEnded: %d", ticks);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!Config.DEBUG_MODE.get()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        LocalPlayer player = mc.player;
        if (player == null) return;

        GuiGraphics graphics = event.getGuiGraphics();
        Font font = mc.font;
        int y = Y_PAD;

        RailGrindDebugSyncPayload synced = RailGrindDebugSyncCache.getFresh();
        boolean hasGrind;
        String[] grindLines;
        int overlapTicks;
        int fallImmunityTicks;
        int startCooldownTicks;
        boolean hasLastDrop;
        RailGrindHandler.StopReason lastDropReason;
        int lastDropTicksSinceGraceEnded;

        if (synced != null) {
            hasGrind = synced.hasGrindState();
            grindLines = hasGrind ? new String[]{
                String.format(Locale.ROOT, "currentSpeed:     %.4f", synced.currentSpeed()),
                String.format(Locale.ROOT, "targetSpeed:      %.4f", synced.targetSpeed()),
                String.format(Locale.ROOT, "accel:            %.4f", synced.acceleration()),
                String.format(Locale.ROOT, "TOP_SPEED:        %.4f", synced.topSpeed()),
                String.format(Locale.ROOT, "experiencedSlope: %+.4f", synced.experiencedSlope()),
                String.format(Locale.ROOT, "experiencedCurve: %+.4f", synced.experiencedCurve()),
                String.format(Locale.ROOT, "position:         %.2f / %.2f", synced.position(), synced.edgeLength()),
                String.format(Locale.ROOT, "stuckTicks:       %d", synced.stuckTicks()),
                String.format(Locale.ROOT, "totalTicks:       %d", synced.totalTicks()),
                String.format(Locale.ROOT, "lateralSign:      %+.0f", synced.lateralSign()),
                String.format(Locale.ROOT, "edge.isTurn():    %b", synced.edgeIsTurn()),
                String.format(Locale.ROOT, "crouchAccelerating: %b", synced.crouchAccelerating()),
                String.format(Locale.ROOT, "collidingWithTrain: %b", synced.collidingWithTrain()),
                formatDriftMargin(synced.driftMargin()),
                String.format(Locale.ROOT, "reattachGraceTicks: %d", synced.reattachGraceTicks()),
                formatTicksSinceGraceEnded(synced.ticksSinceGraceEnded()),
            } : null;
            overlapTicks = synced.trainOverlapTicks();
            fallImmunityTicks = synced.fallImmunityTicks();
            startCooldownTicks = synced.startCooldownTicks();
            hasLastDrop = synced.hasLastDrop();
            lastDropReason = RailGrindHandler.StopReason.fromOrdinal(synced.lastDropReasonOrd());
            lastDropTicksSinceGraceEnded = synced.lastDropTicksSinceGraceEnded();
        } else {
            RailGrindHandler.GrindDebugInfo info = RailGrindHandler.getGrindDebugInfo(player);
            hasGrind = info != null;
            grindLines = info == null ? null : new String[]{
                String.format(Locale.ROOT, "currentSpeed:     %.4f", info.currentSpeed()),
                String.format(Locale.ROOT, "targetSpeed:      %.4f", info.targetSpeed()),
                String.format(Locale.ROOT, "accel:            %.4f", info.acceleration()),
                String.format(Locale.ROOT, "TOP_SPEED:        %.4f", info.topSpeed()),
                String.format(Locale.ROOT, "experiencedSlope: %+.4f", info.experiencedSlope()),
                String.format(Locale.ROOT, "experiencedCurve: %+.4f", info.experiencedCurve()),
                String.format(Locale.ROOT, "position:         %.2f / %.2f", info.position(), info.edgeLength()),
                String.format(Locale.ROOT, "stuckTicks:       %d", info.stuckTicks()),
                String.format(Locale.ROOT, "totalTicks:       %d", info.totalTicks()),
                String.format(Locale.ROOT, "lateralSign:      %+.0f", info.lateralSign()),
                String.format(Locale.ROOT, "edge.isTurn():    %b", info.edgeIsTurn()),
                String.format(Locale.ROOT, "crouchAccelerating: %b", info.crouchAccelerating()),
                String.format(Locale.ROOT, "collidingWithTrain: %b", info.collidingWithTrain()),
                formatDriftMargin(info.driftMargin()),
                String.format(Locale.ROOT, "reattachGraceTicks: %d", info.reattachGraceTicks()),
                formatTicksSinceGraceEnded(info.ticksSinceGraceEnded()),
            };
            overlapTicks = RailGrindHandler.getTrainOverlapTicks(player);
            fallImmunityTicks = RailGrindHandler.getFallImmunityRemaining(player);
            startCooldownTicks = RailGrindHandler.getStartCooldownRemaining(player);
            RailGrindHandler.LastDropHudState lastDrop = RailGrindHandler.getLastDropHudState(player);
            hasLastDrop = lastDrop != null;
            lastDropReason = hasLastDrop ? lastDrop.reason() : RailGrindHandler.StopReason.UNKNOWN;
            lastDropTicksSinceGraceEnded = hasLastDrop ? lastDrop.ticksSinceGraceEnded() : -1;
        }

        if (hasGrind && grindLines != null) {
            for (String line : grindLines) {
                graphics.drawString(font, line, X_PAD, y, COLOR, true);
                y += LINE_HEIGHT;
            }
            y += LINE_HEIGHT / 2;
        }

        String[] overlapLines = {
            String.format(Locale.ROOT, "intersectingTrainAABB: %b", overlapTicks > 0),
            String.format(Locale.ROOT, "trainOverlapTicks: %d", overlapTicks),
            String.format(Locale.ROOT, "fallImmunityTicks: %d", fallImmunityTicks),
            String.format(Locale.ROOT, "RailGrindCooldownTicks: %d", startCooldownTicks),
        };
        for (String line : overlapLines) {
            graphics.drawString(font, line, X_PAD, y, COLOR, true);
            y += LINE_HEIGHT;
        }

        if (hasLastDrop) {
            y += LINE_HEIGHT / 2;
            String[] lastDropLines = {
                "Last grind ended:",
                String.format(Locale.ROOT, " reason:                   %s", lastDropReason.displayName),
                lastDropTicksSinceGraceEnded < 0
                        ? " lastTicksSinceGraceEnded: in grace"
                        : String.format(Locale.ROOT, " lastTicksSinceGraceEnded: %d", lastDropTicksSinceGraceEnded),
            };
            for (String line : lastDropLines) {
                graphics.drawString(font, line, X_PAD, y, COLOR, true);
                y += LINE_HEIGHT;
            }
        }

        ClientInputHandler.LastRightClickInfo rc = ClientInputHandler.lastRightClickInfo;
        if (rc != null) {
            y += LINE_HEIGHT / 2;
            String[] rcLines = {
                "Last R-Click (empty hand):",
                String.format(Locale.ROOT, " gameTime:        %d", rc.gameTimeStamp()),
                String.format(Locale.ROOT, " hitType:         %s", rc.hitType()),
                String.format(Locale.ROOT, " hitBlockPos:     %s", rc.hitBlockPos() == null ? "<none>" : rc.hitBlockPos()),
                String.format(Locale.ROOT, " hitLocation:     %s", rc.hitLocation() == null ? "<none>" : rc.hitLocation()),
                String.format(Locale.ROOT, " parentBlock:     %s", rc.parentBlockId()),
                String.format(Locale.ROOT, " parentStdTrack:  %b", rc.parentIsStandardTrack()),
                String.format(Locale.ROOT, " detection:       %s", rc.detectionSource()),
            };
            for (String line : rcLines) {
                graphics.drawString(font, line, X_PAD, y, COLOR, true);
                y += LINE_HEIGHT;
            }
        }
    }
}
