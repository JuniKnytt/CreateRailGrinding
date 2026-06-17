package net.juniknytt.createrailgrinding.client;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.client.extensions.common.IClientMobEffectExtensions;

public final class SonicWindClientExtensions implements IClientMobEffectExtensions {

    private static final ResourceLocation EFFECT_TEXTURE =
            new ResourceLocation(RailGrind.MODID, "textures/mob_effect/effect.png");
    private static final int ICON_SIZE = 18;

    @Override
    public boolean renderGuiIcon(MobEffectInstance instance, Gui gui, GuiGraphics guiGraphics, int x, int y, float z, float alpha) {

        guiGraphics.setColor(1.0F, 1.0F, 1.0F, alpha);
        guiGraphics.blit(EFFECT_TEXTURE, x + 3, y + 3, 0, 0.0F, 0.0F, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
        guiGraphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        return true;
    }

    @Override
    public boolean renderInventoryIcon(MobEffectInstance instance, EffectRenderingInventoryScreen<?> screen, GuiGraphics guiGraphics, int x, int y, int blitOffset) {

        guiGraphics.blit(EFFECT_TEXTURE, x, y + 7, blitOffset, 0.0F, 0.0F, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
        return true;
    }
}
