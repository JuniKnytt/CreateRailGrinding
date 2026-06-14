package net.juniknytt.createrailgrinding.mixin.client;

import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.juniknytt.createrailgrinding.client.ClientInputHandler;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Camera.class)
public abstract class JumpChargeCameraMixin {
    private static final float CROUCH_EYE_DELTA = 0.35F;

    @Redirect(
        method = "tick()V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getEyeHeight()F")
    )
    private float createrailgrinding$lowerOnCharge(Entity entity) {
        float vanilla = entity.getEyeHeight();
        if (!(entity instanceof Player player)) return vanilla;
        LocalPlayer localPlayer = Minecraft.getInstance().player;
        if (entity != localPlayer) return vanilla;
        if (!BalancingPoseTracker.isBalancing(player)) return vanilla;
        if (ClientInputHandler.isCharging()) return vanilla - CROUCH_EYE_DELTA;
        if (ClientInputHandler.isAccelerateHeld(localPlayer)) return vanilla - CROUCH_EYE_DELTA;
        return vanilla;
    }
}
