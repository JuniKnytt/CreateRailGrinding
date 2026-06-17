package net.juniknytt.createrailgrinding.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.juniknytt.createrailgrinding.client.RailGrindLeanTracker;
import net.juniknytt.createrailgrinding.client.RailGrindModelTilt;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererTiltMixin {

    private static final float LEAN_MAX_DEGREES = 10.0f;

    private static final float LEAN_EPSILON = 1e-4f;

    @Inject(
        method = "setupRotations(Lnet/minecraft/world/entity/LivingEntity;Lcom/mojang/blaze3d/vertex/PoseStack;FFF)V",
        at = @At("HEAD")
    )
    private void createrailgrinding$applyGrindTilt(
            LivingEntity entity, PoseStack poseStack,
            float ageInTicks, float rotationYaw, float partialTicks,
            CallbackInfo ci) {
        if (!(entity instanceof Player player)) return;
        if (!BalancingPoseTracker.isBalancing(player)) return;

        float lean = RailGrindLeanTracker.getRenderLean(player, partialTicks);
        if (Math.abs(lean) >= LEAN_EPSILON) {
            float angleRad = lean * (float) Math.toRadians(LEAN_MAX_DEGREES);

            float yRotDeg = player.getViewYRot(partialTicks);
            float yRotRad = yRotDeg * (float) (Math.PI / 180.0);
            float fx = -Mth.sin(yRotRad);
            float fz = Mth.cos(yRotRad);

            poseStack.mulPose(new Quaternionf().rotationAxis(angleRad, fx, 0f, fz));
        }

        Quaternionf slope = RailGrindModelTilt.getRenderTilt(player, partialTicks);
        if (slope != null) {
            poseStack.mulPose(slope);
        }
    }
}
