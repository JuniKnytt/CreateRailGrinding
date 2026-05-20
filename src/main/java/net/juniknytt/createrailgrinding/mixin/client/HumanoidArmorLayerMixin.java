package net.juniknytt.createrailgrinding.mixin.client;

import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.cosmetic.CustomBootSkin;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.ClientHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(HumanoidArmorLayer.class)
public abstract class HumanoidArmorLayerMixin {

    @Redirect(
            method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/model/HumanoidModel;FFFFFF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/client/ClientHooks;getArmorTexture(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ArmorMaterial$Layer;ZLnet/minecraft/world/entity/EquipmentSlot;)Lnet/minecraft/resources/ResourceLocation;"
            )
    )
    private ResourceLocation createrailgrinding$redirectArmorTexture(
            Entity entity, ItemStack stack, ArmorMaterial.Layer layer, boolean innerModel, EquipmentSlot slot) {
        ResourceLocation original = ClientHooks.getArmorTexture(entity, stack, layer, innerModel, slot);
        if (!CustomBootSkin.matches(stack)) return original;
        String layerSuffix = innerModel ? "_layer_2" : "_layer_1";
        return ResourceLocation.fromNamespaceAndPath(
                RailGrind.MODID, "textures/models/armor/custom_diving" + layerSuffix + ".png");
    }
}
