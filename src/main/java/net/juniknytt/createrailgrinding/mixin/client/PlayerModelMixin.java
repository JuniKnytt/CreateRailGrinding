package net.juniknytt.createrailgrinding.mixin.client;

import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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

        // Faint wobble — three out-of-phase sines so the body rocks subtly without looking mechanical.
        float wobbleZ = Mth.sin(ageInTicks * 0.18F) * 0.04F;          // side-to-side lean
        float wobbleY = Mth.sin(ageInTicks * 0.13F) * 0.025F;         // body twist
        float wobbleX = Mth.sin(ageInTicks * 0.31F) * 0.02F;          // forward bob
        float armWobble = Mth.sin(ageInTicks * 0.22F) * 0.05F;        // arms breathe

        this.body.xRot = wobbleX;
        this.body.yRot = wobbleY;
        this.body.zRot = wobbleZ;

        this.head.xRot = 0.0F;
        this.head.yRot = -wobbleY;        // counter-twist so the head still tracks the camera
        this.head.zRot = -wobbleZ * 0.5F;

        this.rightArm.xRot = 0.0F;
        this.rightArm.yRot = 0.0F;
        this.rightArm.zRot = Mth.PI / 2.0F + armWobble;

        this.leftArm.xRot = 0.0F;
        this.leftArm.yRot = 0.0F;
        this.leftArm.zRot = -Mth.PI / 2.0F - armWobble;

        this.rightLeg.xRot = 0.0F;
        this.rightLeg.yRot = 0.0F;
        this.rightLeg.zRot = wobbleZ * 0.3F;

        this.leftLeg.xRot = 0.0F;
        this.leftLeg.yRot = 0.0F;
        this.leftLeg.zRot = wobbleZ * 0.3F;

        // Vanilla setupAnim synced the outer skin layer against the walk-cycle pose; redo it now.
        this.leftSleeve.copyFrom(this.leftArm);
        this.rightSleeve.copyFrom(this.rightArm);
        this.leftPants.copyFrom(this.leftLeg);
        this.rightPants.copyFrom(this.rightLeg);
        this.jacket.copyFrom(this.body);
        this.hat.copyFrom(this.head);
    }
}
