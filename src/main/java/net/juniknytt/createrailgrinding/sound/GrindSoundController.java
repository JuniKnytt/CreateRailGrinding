package net.juniknytt.createrailgrinding.sound;

import net.juniknytt.createrailgrinding.Config;
import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side controller for rail-grind sound playback.
 * Reads top speed from {@link Config#TOP_GRIND_SPEED} (synced from the server) so the slow/fast
 * loop crossover thresholds track the configured grind ceiling.
 *
 * Tracks per-player loop state in {@link #STATES} so every grinding player visible to this
 * client (local or remote) gets their own slow/fast loop pair, positioned at that player's
 * coordinates. The sound engine attenuates by distance from the listener, so the local player
 * hears their own grind loud and remote grinds fade with range.
 */
@EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
public final class GrindSoundController {
    private static double maxSpeed()      { return Config.TOP_GRIND_SPEED.get(); }
    private static double slowRampEnd()   { return maxSpeed() * 0.50; }   // low-end slow pitch/volume ramps end at 50% of cruise
    private static double fastRampStart() { return maxSpeed() * 0.50; }   // fast loop is silent at/below 50% of cruise
    private static double fastRampEnd()   { return maxSpeed() * 0.75; }   // fast reaches full volume at 75% of cruise
    // High-end pitch ramp ceiling. Actual achievable per-tick speeds exceed TOP_GRIND_SPEED:
    // entry-boost is clamped to MAX_STEP=2.0 (RailGrindHandler.java:178), downhill can reach
    // topSpeed × 1.9 via DOWNHILL_FACTOR, and Config.CRUISE_GRIND_SPEED can be configured up
    // to 2.0. Anchoring the second pitch ramp to this ceiling lets the audio respond across
    // the full achievable range instead of saturating at NORMAL_PITCH around cruise.
    private static final double SPEED_CEILING = 2.0;                // mirrors RailGrindHandler.MAX_STEP / RailGrindClientMotion.MAX_STEP
    private static final float  SLOW_PITCH_START = 0.25f;           // slow pitch at speed 0
    private static final float  NORMAL_PITCH = 1.0f;                // unmodulated pitch — held between slowRampEnd and TOP_GRIND_SPEED
    private static final float  HIGH_PITCH_MAX = 1.25f;             // slow pitch at SPEED_CEILING — captures entry-boost / downhill speeds that were previously inaudible
    private static final float  SLOW_VOLUME_START = 0.25f;          // slow volume at speed 0
    private static final float  SLOW_VOLUME_MAX = 0.5f;             // slow volume at/above SLOW_RAMP_END
    private static final float  FAST_VOLUME_MAX = 0.35f;            // fast volume at/above FAST_RAMP_END
    private static final float  COLLIDE_VOLUME = 0.6f;              // one-shot grind-start (lowered from 1.0)
    private static final double SPEED_SMOOTH_RATE = 0.08;           // max per-tick change in audible speed. Server max accel (boosted) ≈ 0.06/tick — anything above that is treated as an abrupt change (snap-to-rail catch-up, network blip) and rate-limited so the sound doesn't tear.
    // First-order EMA on the slow-loop output pitch. Speed smoothing alone leaves
    // perceptible vibrato when speed bounces rapidly (curve-induced target-speed swings,
    // sneak press/release) because the pitch curve is piecewise-linear in speed. The
    // EMA gives the pitch its own exponential ease so rapid back-and-forth averages out
    // instead of audibly modulating. 0.10 → ~50% gap closed in 7 ticks (~350 ms), ~90%
    // in 22 ticks (~1.1 s). Fast enough that "0 → cruise" still feels responsive, slow
    // enough that single-curve speed dips don't propagate into audible pitch wobble.
    private static final float  SLOW_PITCH_EASE_RATE = 0.10f;
    // Same EMA shape applied to the fast loop's volume. Speed bouncing across the
    // 50%-of-cruise rampStart (curve decel, sneak press/release) used to produce a brief
    // fade-in/out clatter even after Solution 1 stopped the create/destroy thrash — the
    // raw volume curve was piecewise-linear in speed and tracked every twitch. Same 0.10
    // rate as the pitch ease so the two loops move together.
    private static final float  FAST_VOLUME_EASE_RATE = 0.10f;

    private static final Map<UUID, PlayerSoundState> STATES = new HashMap<>();

    private GrindSoundController() {}

    /**
     * Fire-and-forget one-shot played at the grinding player's position. Called from
     * {@code ClientPayloadHandler.handleSync} on grind start for every player (local or
     * remote), so distance attenuation is needed — using {@code playLocalSound} routes
     * through the sound engine's 3D pipeline so the local listener hears their own start
     * full-volume and remote starts attenuate with range.
     */
    public static void playCollide(Player player) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        mc.level.playLocalSound(
            player.getX(), player.getY(), player.getZ(),
            ModSounds.GRIND_COLLIDE.get(), SoundSource.PLAYERS,
            COLLIDE_VOLUME * userVolume(), 1.0f, false);
    }

    @SubscribeEvent
    public static void onClientPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        // PlayerTickEvent fires on both physical sides; in single-player the integrated
        // server runs in the same JVM as the @Dist.CLIENT subscriber, so we'd otherwise
        // process ServerPlayer ticks too and double-spawn loops. Filter to client-side.
        if (!player.level().isClientSide()) return;

        // On a dedicated server, the SERVER config syncs to the client during the
        // configuration phase. The server can mark a player as balancing (via
        // SyncBalanceStatePayload) before that sync completes, so computeSlowPitch ->
        // Config.TOP_GRIND_SPEED.get() can throw IllegalStateException ("Cannot get
        // config value before config is loaded"). Skip the tick until the spec is
        // populated — sound resumes naturally on the next tick after sync lands.
        if (!Config.SERVER_SPEC.isLoaded()) return;

        UUID id = player.getUUID();
        if (!BalancingPoseTracker.isBalancing(player)) {
            PlayerSoundState s = STATES.remove(id);
            if (s != null) s.stopAllLoops();
            return;
        }

        STATES.computeIfAbsent(id, k -> new PlayerSoundState()).tick(player);
    }

    /**
     * Drop all per-player sound state. Called from {@code ClientInputHandler.clearClientGrindState}
     * on login/logout/clone so stale UUIDs from a prior session don't linger across world changes.
     * Active loops self-stop via their tick() check, but the map entries would otherwise leak.
     */
    public static void clearAll() {
        for (PlayerSoundState s : STATES.values()) s.stopAllLoops();
        STATES.clear();
    }

    private static float userVolume() {
        return Config.SOUND_VOLUME.get().floatValue();
    }

    private static float computeSlowPitch(double speed) {
        double lowRampEnd = slowRampEnd();
        if (speed < lowRampEnd) {
            // Low-end ramp: SLOW_PITCH_START at speed 0 up to NORMAL_PITCH at 50% of cruise.
            float t = (float) (speed / lowRampEnd);
            return Mth.lerp(t, SLOW_PITCH_START, NORMAL_PITCH);
        }
        double cruise = maxSpeed();
        if (speed <= cruise) return NORMAL_PITCH;
        // High-end ramp: NORMAL_PITCH at cruise up to HIGH_PITCH_MAX at SPEED_CEILING (MAX_STEP).
        // Without this, entry-boost (clamped to MAX_STEP=2.0) and downhill (up to ~190% of cruise)
        // both played at identical pitch to steady cruise — saturating the audio cue.
        if (speed >= SPEED_CEILING) return HIGH_PITCH_MAX;
        float t = (float) ((speed - cruise) / (SPEED_CEILING - cruise));
        return Mth.lerp(t, NORMAL_PITCH, HIGH_PITCH_MAX);
    }

    private static float computeSlowVolume(double speed) {
        double rampEnd = slowRampEnd();
        if (speed >= rampEnd) return SLOW_VOLUME_MAX;
        float t = (float) Mth.clamp(speed / rampEnd, 0.0, 1.0);
        return Mth.lerp(t, SLOW_VOLUME_START, SLOW_VOLUME_MAX);
    }

    private static float computeFastVolume(double speed) {
        double rampStart = fastRampStart();
        double rampEnd = fastRampEnd();
        if (speed >= rampEnd) return FAST_VOLUME_MAX;
        if (speed <= rampStart) return 0.0f;
        float t = (float) ((speed - rampStart) / (rampEnd - rampStart));
        return t * FAST_VOLUME_MAX;
    }

    private static final class PlayerSoundState {
        GrindLoop slowLoop;
        GrindLoop fastLoop;
        Vec3 prevPos;
        double smoothedSpeed;
        float smoothedSlowPitch = SLOW_PITCH_START;
        float smoothedFastVolume = 0.0f;
        // Cleared after the first tick that observes a real (rawSpeed > 0.01) measurement.
        // Server-side currentSpeed launches at CRUISE_SPEED × Config.CRUISE_GRIND_SPEED
        // (RailGrindHandler.java:353), so the player is already moving at cruise pace on
        // tick 1 — but the client's first rawSpeed reading is 0 (prevPos is null). Without
        // the bypass, smoothedSpeed and the pitch/volume EMAs would all start at zero and
        // audibly ramp up over ~1 s even though the player is at cruise from the start.
        // The bypass snaps every smoothing state to its first real measurement, then the
        // normal EMA shape kicks in tick 3 onward.
        boolean needsSeeding = true;

        void tick(Player player) {
            // Raw speed = 3D position delta per tick. With noPhysics this matches server-side
            // currentSpeed for the local player, and for remote players it tracks the
            // interpolated position the sound engine is also using for spatialization.
            Vec3 currentPos = player.position();
            double rawSpeed = (prevPos == null) ? 0.0 : currentPos.subtract(prevPos).length();
            prevPos = currentPos;

            if (needsSeeding && rawSpeed > 0.01) {
                // First real measurement: snap every smoothing variable to it. Bypasses both
                // the SPEED_SMOOTH_RATE rate-limiter and the pitch/volume EMAs so the loops
                // start at the launched-speed pitch/volume instead of easing up from zero.
                smoothedSpeed = rawSpeed;
                smoothedSlowPitch = computeSlowPitch(rawSpeed);
                smoothedFastVolume = computeFastVolume(rawSpeed);
                needsSeeding = false;
            } else {
                // Rate-limit smoothedSpeed approach to rawSpeed. A one-tick snap-to-rail
                // catch-up can briefly spike well past any plausible accel; rate-limiting
                // here fades spikes in/out instead of slamming the pitch/volume transitions.
                double delta = rawSpeed - smoothedSpeed;
                if (Math.abs(delta) > SPEED_SMOOTH_RATE) {
                    smoothedSpeed += Math.signum(delta) * SPEED_SMOOTH_RATE;
                } else {
                    smoothedSpeed = rawSpeed;
                }
                // 1st-order EMA on slow pitch and fast volume — see SLOW_PITCH_EASE_RATE
                // comment. Keeps rapid back-and-forth speed swings (curve targets, sneak
                // toggling) from translating to audible vibrato in the loops.
                smoothedSlowPitch = Mth.lerp(SLOW_PITCH_EASE_RATE, smoothedSlowPitch, computeSlowPitch(smoothedSpeed));
                smoothedFastVolume = Mth.lerp(FAST_VOLUME_EASE_RATE, smoothedFastVolume, computeFastVolume(smoothedSpeed));
            }

            // Both loops are created once on the first tick and persist for the grind's
            // lifetime. The fast loop's volume is the gate — computeFastVolume returns 0 below
            // 50% of cruise — so the audible behaviour is identical to the old "create/destroy
            // at 50%" scheme. The difference: speed bouncing across the 50% threshold (slope
            // variation, sneak release, curve decel, cross-dim grace) no longer tears down and
            // re-creates the instance, which produced a brief click each transition.
            if (slowLoop == null) {
                slowLoop = new GrindLoop(player, ModSounds.GRIND_SLOW_LOOP.get());
                Minecraft.getInstance().getSoundManager().play(slowLoop);
            }
            if (fastLoop == null) {
                fastLoop = new GrindLoop(player, ModSounds.GRIND_FAST_LOOP.get());
                fastLoop.setPitch(NORMAL_PITCH);
                Minecraft.getInstance().getSoundManager().play(fastLoop);
            }
            slowLoop.setPitch(smoothedSlowPitch);
            slowLoop.setVolume(computeSlowVolume(smoothedSpeed) * userVolume());
            fastLoop.setVolume(smoothedFastVolume * userVolume());
        }

        void stopAllLoops() {
            if (slowLoop != null) {
                slowLoop.cancel();
                slowLoop = null;
            }
            if (fastLoop != null) {
                fastLoop.cancel();
                fastLoop = null;
            }
            smoothedSpeed = 0.0;
            smoothedSlowPitch = SLOW_PITCH_START;
            smoothedFastVolume = 0.0f;
            needsSeeding = true;
        }
    }

    private static final class GrindLoop extends AbstractTickableSoundInstance {
        final SoundEvent kind;
        final Player player;

        GrindLoop(Player player, SoundEvent kind) {
            super(kind, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            this.kind = kind;
            this.player = player;
            this.looping = true;
            this.delay = 0;
            // Must be > 0 here: SoundEngine.play() discards sounds that start at zero volume,
            // and setVolume() on later ticks can't requeue a sound that was never added.
            // The first tick (or the call site, for the fast loop) overwrites this immediately.
            this.volume = 0.05f;
            this.pitch = SLOW_PITCH_START;
            this.x = (float) player.getX();
            this.y = (float) player.getY();
            this.z = (float) player.getZ();
        }

        @Override
        public void tick() {
            // Self-terminate when the player is no longer a valid sound source: dead, removed
            // from the world (out-of-range despawn for a remote player), or BalancingPoseTracker
            // says they finished grinding. The state map cleanup happens in onClientPlayerTick,
            // but the sound engine ticks instances independently of player ticks, so this
            // guard runs even if the entity has gone out of range.
            if (!player.isAlive() || !BalancingPoseTracker.isBalancing(player)) {
                stop();
                return;
            }
            x = (float) player.getX();
            y = (float) player.getY();
            z = (float) player.getZ();
        }

        // Inner class can touch inherited `protected` members directly; outer class can't,
        // because `pitch` and `stop()` are declared in net.minecraft.client.resources.sounds
        // (a different package). `stop()` is final in AbstractTickableSoundInstance, so wrap
        // it under a different name rather than overriding.
        void cancel() {
            stop();
        }

        void setPitch(float pitch) {
            this.pitch = pitch;
        }

        void setVolume(float volume) {
            this.volume = volume;
        }
    }
}
