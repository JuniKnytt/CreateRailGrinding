package net.juniknytt.createrailgrinding.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.juniknytt.createrailgrinding.client.HandRenderTracker;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererHandMixin {

    @Inject(
        method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;)V",
        at = @At("HEAD")
    )
    private void createrailgrinding$pushHandFlag(
            PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
            AbstractClientPlayer player, ModelPart hand, ModelPart sleeve,
            CallbackInfo ci) {
        HandRenderTracker.push();
    }

    @Inject(
        method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;)V",
        at = @At("RETURN")
    )
    private void createrailgrinding$popHandFlag(
            PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
            AbstractClientPlayer player, ModelPart hand, ModelPart sleeve,
            CallbackInfo ci) {
        HandRenderTracker.pop();
    }
}
