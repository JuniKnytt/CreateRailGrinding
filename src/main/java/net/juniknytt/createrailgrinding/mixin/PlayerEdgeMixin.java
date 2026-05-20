package net.juniknytt.createrailgrinding.mixin;

import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerEdgeMixin {

    @Inject(
        method = "maybeBackOffFromEdge",
        at = @At("HEAD"),
        cancellable = true
    )
    private void createrailgrinding$bypassWhileGrinding(
            Vec3 vec, MoverType type, CallbackInfoReturnable<Vec3> cir) {
        Player self = (Player) (Object) this;
        if (self.noPhysics || self.isNoGravity()) {
            cir.setReturnValue(vec);
        }
    }
}
