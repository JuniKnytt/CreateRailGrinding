package net.juniknytt.createrailgrinding.mixin;

import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PortalProcessor.class)
public abstract class PortalProcessorInstantMixin {

    @Inject(method = "processPortalTeleportation", at = @At("HEAD"), cancellable = true)
    private void createrailgrinding$instantWhileGrinding(
            ServerLevel level, Entity entity, boolean canChangeDimensions,
            CallbackInfoReturnable<Boolean> cir) {
        if (!canChangeDimensions) return;
        if (!(entity instanceof Player player)) return;
        if (!RailGrindHandler.isGrinding(player)) return;

        if (RailGrindHandler.isOnPostPortalTransitCooldown(player)) return;
        cir.setReturnValue(Boolean.TRUE);
    }
}
