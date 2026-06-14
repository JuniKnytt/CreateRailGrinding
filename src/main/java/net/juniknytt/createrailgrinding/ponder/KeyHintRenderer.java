package net.juniknytt.createrailgrinding.ponder;

import com.mojang.blaze3d.vertex.PoseStack;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

final class KeyHintRenderer {

    private static final int PADDING = 6;
    private static final int HEIGHT = 24;
    private static final int MIN_WIDTH = 24;

    private static final int FRAMEWORK_ICON_BOX = 24;
    private static final int FRAMEWORK_NUB_DROP = FRAMEWORK_ICON_BOX + 8 + 1 + 1;

    private KeyHintRenderer() {}

    static void render(GuiGraphics graphics, String label) {
        Font font = Minecraft.getInstance().font;
        int textWidth = font.width(label);
        int width = Math.max(MIN_WIDTH, textWidth + PADDING * 2);

        PoseStack ms = graphics.pose();
        ms.pushPose();
        ms.scale(1f / 1.5f, 1f / 1.5f, 1f);

        ms.translate(FRAMEWORK_ICON_BOX / 2f, FRAMEWORK_NUB_DROP, 0f);

        PonderUI.renderSpeechBox(graphics, 0, 0, width, HEIGHT, false, Pointing.DOWN, true);

        ms.translate(0, 0, 100);
        graphics.drawString(font, label,
                (width - textWidth) / 2,
                (int) ((HEIGHT - font.lineHeight) / 2f + 2),
                0xFF_EEEEEE, false);
        ms.popPose();
    }
}
