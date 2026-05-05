package net.juniknytt.createrailgrinding.client;

import net.juniknytt.createrailgrinding.Config;
import net.juniknytt.createrailgrinding.RailGrind;
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

/**
 * Debug-only HUD overlay: prints the local player's live grind state
 * (currentSpeed, targetSpeed, accel, slope, etc.) in the top-left corner.
 * Toggled by {@link Config#DEBUG_MODE}; reads {@link RailGrindHandler#getGrindDebugInfo}
 * directly, so values only appear on integrated server / single-player
 * (no client-side sync packet for grind state).
 */
@EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
public final class RailGrindDebugSpeed {
    private static final int X_PAD = 4;
    private static final int Y_PAD = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int COLOR = 0xFFFFFFFF;

    private RailGrindDebugSpeed() {}

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

        RailGrindHandler.GrindDebugInfo info = RailGrindHandler.getGrindDebugInfo(player);
        if (info != null) {
            String[] grindLines = {
                String.format(Locale.ROOT, "currentSpeed:     %.4f", info.currentSpeed()),
                String.format(Locale.ROOT, "targetSpeed:      %.4f", info.targetSpeed()),
                String.format(Locale.ROOT, "accel:            %.4f", info.acceleration()),
                String.format(Locale.ROOT, "TOP_SPEED:        %.4f", info.topSpeed()),
                String.format(Locale.ROOT, "experiencedSlope: %+.4f", info.experiencedSlope()),
                String.format(Locale.ROOT, "position:         %.2f / %.2f", info.position(), info.edgeLength()),
                String.format(Locale.ROOT, "stuckTicks:       %d", info.stuckTicks()),
                String.format(Locale.ROOT, "totalTicks:       %d", info.totalTicks()),
                String.format(Locale.ROOT, "lateralSign:      %+.0f", info.lateralSign()),
                String.format(Locale.ROOT, "edge.isTurn():    %b", info.edgeIsTurn()),
                String.format(Locale.ROOT, "shiftHeld:        %b", info.shiftHeld()),
                String.format(Locale.ROOT, "collidingWithTrain: %b", info.collidingWithTrain()),
            };
            for (String line : grindLines) {
                graphics.drawString(font, line, X_PAD, y, COLOR, true);
                y += LINE_HEIGHT;
            }
            y += LINE_HEIGHT / 2;  // small gap before the always-on train-overlap section
        }

        // Train-overlap crush state — rendered even when not grinding so the gate's
        // behavior is still visible from outside a grind (e.g., standing inside a parked
        // carriage, or right after the counter kicks the player off the rail). Putting
        // these inside the grind block instead would blank the count the same tick the
        // kick fires — gs disappears before the next render — so the user would never
        // actually see the value reach the threshold. Bool is derived from the counter
        // (count > 0 ↔ overlap this tick) since tickTrainOverlap removes the entry on
        // the first miss.
        int overlapTicks = RailGrindHandler.getTrainOverlapTicks(player);
        String[] overlapLines = {
            String.format(Locale.ROOT, "intersectingTrainAABB: %b", overlapTicks > 0),
            String.format(Locale.ROOT, "trainOverlapTicks: %d", overlapTicks),
        };
        for (String line : overlapLines) {
            graphics.drawString(font, line, X_PAD, y, COLOR, true);
            y += LINE_HEIGHT;
        }
    }
}
