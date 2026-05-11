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

/**
 * Lowers the camera's eye-height target by the standing-vs-crouching delta
 * (1.62 − 1.27 = 0.35) for two grind cases that vanilla wouldn't already lower:
 * <ol>
 *   <li>Local player charging a dismount jump — their POV should drop to match
 *       the wind-up stance even though they're not technically crouching.
 *   <li>Local player accelerating via the "Grind Crouch Accelerate Override"
 *       key while {@link Config#OVERRIDE_KEYBINDINGS} is enabled. The
 *       override replaces shift, so vanilla never sees a sneak input and
 *       wouldn't otherwise drop the camera; we mimic the sneak-camera effect
 *       so the override feels visually identical to the shift-accelerate path.
 * </ol>
 *
 * <p>Implemented as a Redirect on the single {@code entity.getEyeHeight()}
 * call inside {@link Camera#tick}. Camera's tick smooths its internal
 * {@code eyeHeight} field toward that value at 50% per tick — the same path
 * vanilla uses to ease the camera up/down when sneak starts/ends — so the
 * transition into and out of the lowered POV reuses that smoothing for free.
 * (In 1.21.1 the smoothing lives in {@code tick()}; {@code setup()} just reads
 * the already-smoothed {@code eyeHeight} field via an old/new lerp.)
 *
 * <p>Strictly cosmetic: {@link Entity#getEyeHeight()} is left untouched at
 * every other call site (raycast origin, hitbox calculations, server-side
 * checks). Only the camera's local copy is shifted, and only for the local
 * player while one of the conditions above is true.
 */
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
