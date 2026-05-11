package net.juniknytt.createrailgrinding.ponder;

import com.mojang.blaze3d.vertex.PoseStack;

import net.createmod.catnip.gui.element.BoxElement;
import net.createmod.catnip.gui.element.ScreenElement;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Companion to {@link JumpKeyHint} for the combined Sneak + Jump prompt — Ponder has
 * no public hook for stacking two modifier labels in one input bubble, so this
 * {@link ScreenElement} renders the literal string in the input window's icon slot via
 * {@code .showing(SneakJumpKeyHint.SNEAKPLUSJUMP)}. Counter-scales the 1.5x icon
 * transform so the label reads at the same size as the built-in "Sneak +" / "Ctrl +"
 * labels, AND draws its own background box because Ponder hard-codes the icon-slot
 * width to 24 px in InputWindowElement.render() — without our extension box the right
 * half of "Sneak + Jump" would spill outside the speech bubble.
 */
public final class SneakJumpKeyHint implements ScreenElement {

    public static final ScreenElement SNEAKPLUSJUMP = new SneakJumpKeyHint("Sneak + Jump");

    private final String label;

    private SneakJumpKeyHint(String label) {
        this.label = label;
    }

    @Override
    public void render(GuiGraphics graphics, int x, int y) {
        Font font = Minecraft.getInstance().font;
        PoseStack ms = graphics.pose();
        ms.pushPose();
        // InputWindowElement scales icons by 1.5x; undo that so the label reads at the
        // same size as the existing "Sneak +" / "Ctrl +" labels.
        ms.scale(1f / 1.5f, 1f / 1.5f, 1f);

        int textWidth = font.width(label);
        int padX = 4;
        int boxW = textWidth + padX * 2;
        int boxH = 24;

        // Mask the truncated speech-bubble right edge with a Ponder-styled background
        // wide enough to contain the full label.
        new BoxElement()
                .withBackground(PonderUI.BACKGROUND_FLAT)
                .at(-padX, 0, 0)
                .withBounds(boxW, boxH)
                .render(graphics);

        // Match Ponder's key-text Y formula exactly: (height - lineHeight)/2 + 2,
        // where height = 24 when an icon is present.
        int textY = (int) ((24 - font.lineHeight) / 2f + 2);
        graphics.drawString(font, label, 0, textY, 0xFF_EEEEEE, false);
        ms.popPose();
    }
}
