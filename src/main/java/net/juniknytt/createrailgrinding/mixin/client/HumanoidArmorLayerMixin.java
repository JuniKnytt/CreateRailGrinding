package net.juniknytt.createrailgrinding.mixin.client;

import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.cosmetic.CustomBootSkin;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HumanoidArmorLayer.class)
public abstract class HumanoidArmorLayerMixin {

    @Inject(method = "getArmorResource", at = @At("HEAD"), cancellable = true, remap = false)
    private void createrailgrinding$redirectArmorTexture(
            Entity entity, ItemStack stack, EquipmentSlot slot, @Nullable String type,
            CallbackInfoReturnable<ResourceLocation> cir) {
        if (!CustomBootSkin.matches(stack)) return;
        String layerSuffix = (slot == EquipmentSlot.LEGS) ? "_layer_2" : "_layer_1";
        cir.setReturnValue(new ResourceLocation(
                RailGrind.MODID, "textures/models/armor/custom_diving" + layerSuffix + ".png"));
    }
}
