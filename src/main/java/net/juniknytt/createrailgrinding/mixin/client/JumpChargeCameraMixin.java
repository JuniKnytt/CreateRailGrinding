package net.juniknytt.createrailgrinding.mixin.client;

import net.juniknytt.createrailgrinding.Config;
import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.juniknytt.createrailgrinding.client.ClientInputHandler;
import net.juniknytt.createrailgrinding.client.ModKeyMappings;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Camera.class)
public abstract class JumpChargeCameraMixin {
    private static final float SNEAK_EYE_DELTA = 0.35F;

    @Redirect(
        method = "tick()V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getEyeHeight()F")
    )
    private float createrailgrinding$lowerOnCharge(Entity entity) {
        float vanilla = entity.getEyeHeight();
        if (!(entity instanceof Player player)) return vanilla;
        if (entity != Minecraft.getInstance().player) return vanilla;
        if (!BalancingPoseTracker.isBalancing(player)) return vanilla;
        if (ClientInputHandler.isCharging()) return vanilla - SNEAK_EYE_DELTA;
        if (isCrouchAccelerateOverrideHeld()) return vanilla - SNEAK_EYE_DELTA;
        return vanilla;
    }

    private static boolean isCrouchAccelerateOverrideHeld() {
        if (!Config.OVERRIDE_KEYBINDINGS.get()) return false;
        if (!ModKeyMappings.isAccelOverrideBound()) return false;
        return ModKeyMappings.GRIND_CROUCH_ACCELERATE_OVERRIDE.isDown();
    }
}
