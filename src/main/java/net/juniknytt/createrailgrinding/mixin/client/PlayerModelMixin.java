package net.juniknytt.createrailgrinding.mixin.client;

import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.juniknytt.createrailgrinding.client.ClientInputHandler;
import net.juniknytt.createrailgrinding.client.HandRenderTracker;
import net.juniknytt.createrailgrinding.client.RailGrindAccelTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin extends HumanoidModel<LivingEntity> {

    private PlayerModelMixin(ModelPart root) {
        super(root);
    }

    @Shadow @Final public ModelPart leftSleeve;
    @Shadow @Final public ModelPart rightSleeve;
    @Shadow @Final public ModelPart leftPants;
    @Shadow @Final public ModelPart rightPants;
    @Shadow @Final public ModelPart jacket;

    @Inject(
        method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
        at = @At("TAIL")
    )
    private void createrailgrinding$applyBalancingPose(
            LivingEntity entity,
            float limbSwing, float limbSwingAmount,
            float ageInTicks, float netHeadYaw, float headPitch,
            CallbackInfo ci) {
        if (!(entity instanceof Player player)) return;
        if (!BalancingPoseTracker.isBalancing(player)) return;

        if (HandRenderTracker.isRendering()) return;

        float wobbleZ = Mth.sin(ageInTicks * 0.18F) * 0.01F;
        float wobbleY = Mth.sin(ageInTicks * 0.13F) * 0.05F;
        float wobbleX = Mth.sin(ageInTicks * 0.31F) * 0.01F;
        float armWobble = Mth.sin(ageInTicks * 0.22F) * 0.1F;

        boolean useBoostPose = RailGrindAccelTracker.isAccelerating(player.getUUID())
            || (entity == Minecraft.getInstance().player && ClientInputHandler.isCharging());

        if (useBoostPose) {
            createrailgrinding$applyBoostPose(wobbleX, wobbleY, wobbleZ, armWobble);
        } else {
            createrailgrinding$applyNormalPose(wobbleX, wobbleY, wobbleZ, armWobble);
        }

        this.leftSleeve.copyFrom(this.leftArm);
        this.rightSleeve.copyFrom(this.rightArm);
        this.leftPants.copyFrom(this.leftLeg);
        this.rightPants.copyFrom(this.rightLeg);
        this.jacket.copyFrom(this.body);
        this.hat.copyFrom(this.head);
    }

    @Unique
    private void createrailgrinding$applyNormalPose(
            float wobbleX, float wobbleY, float wobbleZ, float armWobble) {
        this.body.xRot = 0.3F + wobbleX;
        this.body.yRot = -0.5F + wobbleY;
        this.body.zRot = 0.0F + wobbleZ;
        this.body.y = 2.0F;

        this.head.y = 2.0F;

        this.rightArm.xRot = -1.0218428F;
        this.rightArm.yRot = -0.5830672F;
        this.rightArm.zRot =  0.8771576F + armWobble;
        this.rightArm.y = 3.5F;

        this.leftArm.xRot =  1.1399812F;
        this.leftArm.yRot = -0.7707042F;
        this.leftArm.zRot = -1.2638128F - armWobble;
        this.leftArm.y = 3.5F;

        this.rightLeg.xRot = -1.1550762F;
        this.rightLeg.yRot = -1.0731485F;
        this.rightLeg.zRot =  1.0925566F + wobbleZ * 0.3F;
        this.rightLeg.x = -2.5F;
        this.rightLeg.y = 13.0F;
        this.rightLeg.z = 0.1F;

        this.leftLeg.xRot =  0.4882457F;
        this.leftLeg.yRot = -0.8164470F;
        this.leftLeg.zRot = -0.2542725F + wobbleZ * 0.3F;
        this.leftLeg.x = 2.0F;
        this.leftLeg.y = 13.0F;
        this.leftLeg.z = 3.0F;
    }

    @Unique
    private void createrailgrinding$applyBoostPose(
            float wobbleX, float wobbleY, float wobbleZ, float armWobble) {
        this.body.xRot = 0.2F + wobbleX;
        this.body.yRot = -0.5F + wobbleY;
        this.body.zRot = 0.2F + wobbleZ;
        this.body.y = 2.0F;

        this.head.y = 2.5F;

        this.rightArm.xRot = -1.3218428F;
        this.rightArm.yRot = -0.05F;
        this.rightArm.zRot =  0.8771576F + armWobble;
        this.rightArm.y = 4.5F;
        this.rightArm.z = -1F;

        this.leftArm.xRot =  1.4399812F;
        this.leftArm.yRot = -0.05F;
        this.leftArm.zRot = -1.2638128F - armWobble;
        this.leftArm.y = 4.5F;
        this.leftArm.z = 1F;

        this.rightLeg.xRot = -1.4550762F;
        this.rightLeg.yRot = -1.3731485F;
        this.rightLeg.zRot =  1.0925566F + wobbleZ * 0.3F;

        this.rightLeg.x = -3.5F;
        this.rightLeg.y = 13.0F;
        this.rightLeg.z = 0.8F;

        this.leftLeg.xRot =  0.4882457F;
        this.leftLeg.yRot = -0.8164470F;
        this.leftLeg.zRot = -0.2542725F + wobbleZ * 0.3F;
        this.leftLeg.x = -0.0F;
        this.leftLeg.y = 13.0F;
        this.leftLeg.z = 5.0F;
    }
}
