package net.juniknytt.createrailgrinding.mixin.client;

import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.juniknytt.createrailgrinding.client.RailGrindLeanTracker;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraLeanMixin {

    private static final float LEAN_LATERAL = 0.15f;

    private static final float LEAN_DOWN = 0.05f;

    private static final float LEAN_EPSILON = 1e-4f;

    @Shadow
    protected abstract void move(float zoom, float dy, float dx);

    @Inject(
        method = "setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
        at = @At("TAIL")
    )
    private void createrailgrinding$applyLeanOffset(
            BlockGetter level, Entity entity,
            boolean isThirdPerson, boolean isMirrored, float partialTick,
            CallbackInfo ci) {

        if (entity != Minecraft.getInstance().player) return;
        if (!(entity instanceof Player player)) return;
        if (!BalancingPoseTracker.isBalancing(player)) return;
        float lean = RailGrindLeanTracker.getRenderLean(player, partialTick);
        if (Math.abs(lean) < LEAN_EPSILON) return;
        this.move(0.0f, -LEAN_DOWN * Math.abs(lean), LEAN_LATERAL * lean);
    }
}
