package net.juniknytt.createrailgrinding.mixin.client;

import net.juniknytt.createrailgrinding.client.InventoryRenderTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wraps {@link InventoryScreen#renderEntityInInventory} with {@link InventoryRenderTracker}
 * push/pop so grind-pose mixins can skip pose application for that one render pass. This is
 * the single chokepoint every inventory entity-portrait goes through (survival, creative,
 * smithing, beacon, etc.), so one mixin covers them all.
 *
 * <p>Freecam, replay-mod, and shoulder-surfing camera mods don't go through this method —
 * they render the player via the in-world {@code LevelRenderer} path — so the tracker stays
 * false and the grind pose still applies for them.
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenRenderMixin {

    @Inject(
        method = "renderEntityInInventory(Lnet/minecraft/client/gui/GuiGraphics;FFFLorg/joml/Vector3f;Lorg/joml/Quaternionf;Lorg/joml/Quaternionf;Lnet/minecraft/world/entity/LivingEntity;)V",
        at = @At("HEAD")
    )
    private static void createrailgrinding$pushInventoryFlag(
            GuiGraphics guiGraphics, float x, float y, float scale,
            Vector3f translate, Quaternionf pose, Quaternionf cameraOrientation,
            LivingEntity entity, CallbackInfo ci) {
        InventoryRenderTracker.push();
    }

    @Inject(
        method = "renderEntityInInventory(Lnet/minecraft/client/gui/GuiGraphics;FFFLorg/joml/Vector3f;Lorg/joml/Quaternionf;Lorg/joml/Quaternionf;Lnet/minecraft/world/entity/LivingEntity;)V",
        at = @At("RETURN")
    )
    private static void createrailgrinding$popInventoryFlag(
            GuiGraphics guiGraphics, float x, float y, float scale,
            Vector3f translate, Quaternionf pose, Quaternionf cameraOrientation,
            LivingEntity entity, CallbackInfo ci) {
        InventoryRenderTracker.pop();
    }
}
