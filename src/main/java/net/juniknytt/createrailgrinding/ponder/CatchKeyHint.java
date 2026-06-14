package net.juniknytt.createrailgrinding.ponder;

import net.createmod.catnip.gui.element.ScreenElement;
import net.juniknytt.createrailgrinding.client.ModKeyMappings;
import net.minecraft.client.gui.GuiGraphics;

public final class CatchKeyHint implements ScreenElement {

    public static final ScreenElement CATCH = new CatchKeyHint();

    private CatchKeyHint() {}

    @Override
    public void render(GuiGraphics graphics, int x, int y) {
        KeyHintRenderer.render(graphics, ModKeyMappings.CATCH.getTranslatedKeyMessage().getString());
    }
}
