package net.juniknytt.createrailgrinding.ponder;

import com.mojang.blaze3d.vertex.PoseStack;

import net.createmod.catnip.gui.element.ScreenElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

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

        ms.scale(1f / 1.5f, 1f / 1.5f, 1f);

        int textY = (int) ((24 - font.lineHeight) / 2f + 2);
        graphics.drawString(font, label, 0, textY, 0xFF_EEEEEE, false);
        ms.popPose();
    }
}
