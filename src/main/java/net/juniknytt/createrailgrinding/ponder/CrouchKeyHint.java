package net.juniknytt.createrailgrinding.ponder;

import net.createmod.catnip.gui.element.ScreenElement;
import net.juniknytt.createrailgrinding.client.ModKeyMappings;
import net.minecraft.client.gui.GuiGraphics;

public final class CrouchKeyHint implements ScreenElement {

    public static final ScreenElement CROUCH = new CrouchKeyHint();

    private CrouchKeyHint() {}

    @Override
    public void render(GuiGraphics graphics, int x, int y) {
        KeyHintRenderer.render(graphics, ModKeyMappings.GRIND_CROUCH.getTranslatedKeyMessage().getString());
    }
}
