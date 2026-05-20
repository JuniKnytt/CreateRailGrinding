package net.juniknytt.createrailgrinding.ponder;

import com.mojang.blaze3d.vertex.PoseStack;

import net.createmod.catnip.gui.element.BoxElement;
import net.createmod.catnip.gui.element.ScreenElement;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

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

        ms.scale(1f / 1.5f, 1f / 1.5f, 1f);

        int textWidth = font.width(label);
        int padX = 4;
        int boxW = textWidth + padX * 2;
        int boxH = 24;

        new BoxElement()
                .withBackground(PonderUI.BACKGROUND_FLAT)
                .at(-padX, 0, 0)
                .withBounds(boxW, boxH)
                .render(graphics);

        int textY = (int) ((24 - font.lineHeight) / 2f + 2);
        graphics.drawString(font, label, 0, textY, 0xFF_EEEEEE, false);
        ms.popPose();
    }
}
