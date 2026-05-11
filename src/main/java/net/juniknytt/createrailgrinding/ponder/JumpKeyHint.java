package net.juniknytt.createrailgrinding.ponder;

import com.mojang.blaze3d.vertex.PoseStack;

import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Stand-in for a missing {@code InputElementBuilder.whileJump()} — Ponder hard-codes
 * {@code whileSneaking()} / {@code whileCTRL()} to its own shared lang keys with no
 * public hook for arbitrary modifier labels. This {@link ScreenElement} is meant to be
 * dropped into the input window's icon slot via {@code .showing(JumpKeyHint.JUMP)};
 * it counter-scales the 1.5x icon transform so the text reads at normal font size.
 */
public final class JumpKeyHint implements ScreenElement {

    public static final ScreenElement JUMP = new JumpKeyHint("Jump");

    private final String label;

    private JumpKeyHint(String label) {
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
        // Match Ponder's key-text Y formula exactly: (height - lineHeight)/2 + 2,
        // where height = 24 when an icon is present.
        int textY = (int) ((24 - font.lineHeight) / 2f + 2);
        graphics.drawString(font, label, 0, textY, 0xFF_EEEEEE, false);
        ms.popPose();
    }
}
