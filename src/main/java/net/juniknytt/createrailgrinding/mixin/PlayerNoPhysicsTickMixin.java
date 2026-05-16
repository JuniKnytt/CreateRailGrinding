package net.juniknytt.createrailgrinding.mixin;

import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.juniknytt.createrailgrinding.client.RailGrindClientMotion;
import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-asserts {@code noPhysics = true} for a grinding/balancing player ON SLOPES
 * immediately after vanilla {@link Player#tick()} forcibly resets it on line 248
 * ({@code this.noPhysics = this.isSpectator();}).
 *
 * <p>Without this mixin, the reset undoes every {@code noPhysics = true} we set —
 * server-side in {@link RailGrindHandler#tick} (re-assert) and client-side via the sync
 * packet — *before* {@code super.tick()} fires {@code LivingEntity.aiStep → travel →
 * Entity.move}, so {@code Entity.move()} reads {@code noPhysics = false} and runs the
 * full collision pipeline. That was the support-blocks-on-slopes bug:
 * the documented "collision bypassed by noPhysics" guarantee silently never applied at
 * motion-time. {@link PlayerEdgeMixin} only handles sneak-edge-clamping, not the broader
 * collide() sweep.
 *
 * <h2>Conditional on slope — latency-resilient sourcing</h2>
 * Only re-assert when {@code Math.abs(slope) > NO_PHYSICS_SLOPE_THRESHOLD} (0.25 ≈ sin
 * 14.5°). The slope source differs by side so the check stays current under any amount
 * of multiplayer latency:
 *
 * <ul>
 *   <li><b>Server</b>: reads {@link RailGrindHandler#getExperiencedSlope}, which returns
 *       {@code GrindState.experiencedSlope} from {@code ACTIVE}. Server-authoritative,
 *       not subject to its own latency.</li>
 *   <li><b>Client</b>: reads {@code self.getDeltaMovement().y / .length()}. This is the
 *       motion vector that {@link net.juniknytt.createrailgrinding.client.RailGrindClientMotion}
 *       just set in {@code ClientTickEvent.Pre} (which fires before {@code Minecraft#tick}
 *       runs {@code level.tickEntities} and consequently this mixin). Its slope is the
 *       slope of the motion about to feed {@code Entity.move()} <i>this tick</i> —
 *       latency-free, derived locally from {@code (currentTarget - playerPos)}.</li>
 * </ul>
 *
 * <h2>Why local-dm instead of a synced server value</h2>
 * The server's {@code gs.experiencedSlope} is computed from the previous tick's motion
 * delta and shipped via {@code RailGrindTargetPayload}. By the time the client consumes
 * it, the value is {@code L + 1} ticks old (where L is one-way latency in ticks). At
 * {@code MAX_STEP=2.0} blocks/tick, even a modest 100 ms ping (~2 ticks of half-RTT)
 * means the synced slope describes a spline position the player has already moved 4-6
 * blocks past — long enough to fully cross a slope→flat transition and leave the
 * mixin re-asserting {@code noPhysics} on flat track that should instead be vanilla-
 * collidable. Sourcing locally eliminates the stale window entirely; the dm value at
 * the injection point is the motion this tick's {@code Entity.move()} is about to apply.
 *
 * <h2>Remote players on this client</h2>
 * For remote (non-local) grinding players, {@code getDeltaMovement()} reflects whatever
 * {@code ClientboundSetEntityMotionPacket} most recently set — typically {@code Vec3.ZERO}
 * since the server doesn't push motion packets for grinding players (their position is
 * synced through standard movement broadcast). That means remote players read slope ≈ 0,
 * the bypass doesn't fire for them, and vanilla collision applies. This is fine because
 * remote players' positions are lerp-driven from {@code ClientboundMoveEntityPacket} every
 * server tick — any momentary {@code Entity.move} snag gets immediately position-
 * overridden by the next packet, so the support-snag bug doesn't visibly manifest for
 * remote observers.
 *
 * <h2>Injection point</h2>
 * {@code @At(INVOKE)} on the {@code updateIsUnderwater()Z} call in {@code Player.tick}
 * (vanilla line 273). That sits cleanly between the {@code noPhysics = isSpectator()}
 * reset on line 248 and the {@code super.tick()} call on line 274 — late enough that
 * vanilla's reset has already happened, early enough that {@code super.tick()} (and the
 * {@code travel → move} chain it invokes) sees our re-asserted value.
 *
 * <p>Descriptor must be {@code ()Z} not {@code ()V} — {@code updateIsUnderwater} returns
 * boolean. Wrong descriptor produces "Critical injection failure ... Scanned 0 target(s).
 * No refMap loaded." at runtime.
 *
 * <p>Gating: on the server, {@link RailGrindHandler#isGrinding} reads the authoritative
 * {@code ACTIVE} map; on the client, {@link BalancingPoseTracker#isBalancing} reads the
 * sync-packet-populated set. Either side's predicate alone is sufficient for its side,
 * and OR-ing both lets the same mixin live in the common (non-side-gated) mixins array
 * without branching on {@code level().isClientSide()} for the gate (the slope-source
 * branch below does need the side check).
 */
@Mixin(Player.class)
public abstract class PlayerNoPhysicsTickMixin {

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;updateIsUnderwater()Z"
        )
    )
    private void createrailgrinding$reassertNoPhysicsForGrind(CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (!(BalancingPoseTracker.isBalancing(self) || RailGrindHandler.isGrinding(self))) {
            return;
        }
        double slope;
        if (self.level().isClientSide()) {
            // Client-side bypass-suspend: if the per-tick forward bb-probe in
            // RailGrindClientMotion saw solid geometry ahead this tick, skip the bypass
            // so vanilla Entity.collide() runs and stops the player on the wall. Without
            // this, the bypass keeps firing for {@code BLOCKED_DROP_TICKS + RTT} ticks
            // between the first probe-hit and the server's stop-and-sync round trip,
            // which is enough to phase through 1-block walls at full grind speed —
            // exactly the end-of-track-on-slope clipping bug. The 3-tick dismount
            // confirm stays in force for the BlockedByObstaclePayload dispatch (still
            // wants false-positive resistance there); only the bypass reacts on tick 1.
            if (RailGrindClientMotion.isObstacleAheadOnSlope()) return;
            // Local-dm-derived slope. Latency-free — see class javadoc.
            Vec3 dm = self.getDeltaMovement();
            double mag = dm.length();
            // 1e-3 guard: at sub-millimetre per-tick magnitude, the ratio is dominated by
            // floating-point noise. Treat as "no slope" so vanilla collision applies on a
            // stationary player (post-stall, end-of-rail, etc.).
            slope = mag > 1e-3 ? dm.y / mag : 0.0;
        } else {
            slope = RailGrindHandler.getExperiencedSlope(self);
        }
        if (Math.abs(slope) > RailGrindHandler.NO_PHYSICS_SLOPE_THRESHOLD) {
            self.noPhysics = true;
        }
    }
}
