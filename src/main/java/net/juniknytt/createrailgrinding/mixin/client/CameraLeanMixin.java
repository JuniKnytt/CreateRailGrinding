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

/**
 * Shifts the camera laterally and slightly down for the local grinding player when their
 * steer-input lean is non-zero, matching the body-lean visual the 3rd-person model renders
 * through {@link net.juniknytt.createrailgrinding.mixin.client.LivingEntityRendererTiltMixin}.
 *
 * <h2>Injection point</h2>
 * TAIL of {@link Camera#setup}, AFTER vanilla's first/third-person position resolution
 * (including the {@code move(-getMaxZoom(4.0), 0, 0)} zoom-back for 3rd person). Calling
 * {@link Camera#move} from here adds the offset in camera-local coordinates on top of the
 * final position, so the lean shift composes cleanly with both view modes.
 *
 * <h2>Sign conventions</h2>
 * Vanilla's {@code Camera.move(zoom, dy, dx)} builds an offset vector {@code (dx, dy, -zoom)}
 * in camera-local coordinates and rotates it into world space. Camera-local +X (the {@code dx}
 * axis) maps to {@code -Camera.left}, i.e. the player's RIGHT side. Therefore:
 * <ul>
 *   <li>{@code zoom} — forward/back along the view ray. Unused here.</li>
 *   <li>{@code dy} — up/down along {@code Camera.up}. Positive = up; we want "slightly down"
 *       so we pass {@code -|lean| * LEAN_DOWN}, scaled by |lean| because down-shift should
 *       feel symmetric for left and right lean.</li>
 *   <li>{@code dx} — left/right along player's right axis. Positive = right; we want
 *       lean=+1 (player presses D) to shift the camera RIGHT, so we pass
 *       {@code +lean * LEAN_LATERAL}.</li>
 * </ul>
 *
 * <h2>Smoothing</h2>
 * Inherited from {@link RailGrindLeanTracker}'s tick-EMA + partial-tick lerp. The shift
 * eases in/out over ~3-4 ticks on key press/release rather than snapping, matching the
 * model-tilt + camera-roll smoothing on the same scalar.
 *
 * <h2>Scope</h2>
 * Local-player only. Remote players' camera offsets are meaningless to this client (their
 * camera isn't this client's camera) — only their model-tilt visual surfaces the lean.
 */
@Mixin(Camera.class)
public abstract class CameraLeanMixin {
    /**
     * Peak lateral offset (blocks) at full lean. ~0.15 reads as a subtle head shift to the
     * inside of a turn rather than a full body slide. Tuned for first-person feel; third-
     * person is less sensitive because the camera is already pulled back several blocks.
     */
    private static final float LEAN_LATERAL = 0.15f;

    /**
     * Peak down offset (blocks) at full lean. Scaled by {@code |lean|} so left and right
     * lean both lower the camera the same amount — the lean visual reads as "duck and shift
     * sideways" rather than "rise on one side, fall on the other". 0.05 is intentionally
     * subtle; visible mostly as a gentle bob in first-person.
     */
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
        // Only apply to the local player's own camera — remote players' positions don't
        // drive this client's Camera.setup invocation, but a third-party mod or future
        // change could call setup with a different Entity, so we gate defensively.
        if (entity != Minecraft.getInstance().player) return;
        if (!(entity instanceof Player player)) return;
        if (!BalancingPoseTracker.isBalancing(player)) return;
        float lean = RailGrindLeanTracker.getRenderLean(player, partialTick);
        if (Math.abs(lean) < LEAN_EPSILON) return;
        this.move(0.0f, -LEAN_DOWN * Math.abs(lean), LEAN_LATERAL * lean);
    }
}
