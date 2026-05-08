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
    private static double slowRampEnd()   { return maxSpeed() * 0.50; }   // slow pitch/volume ramps end at 50%
    private static double fastRampStart() { return maxSpeed() * 0.50; }   // fast loop is silent at/below 50%
    private static double fastRampEnd()   { return maxSpeed() * 0.75; }   // fast reaches full volume at 75%
    private static final float  SLOW_PITCH_START = 0.25f;           // slow pitch at speed 0
    private static final float  NORMAL_PITCH = 1.0f;                // unmodulated pitch
    private static final float  SLOW_VOLUME_START = 0.25f;          // slow volume at speed 0
    private static final float  SLOW_VOLUME_MAX = 0.5f;             // slow volume at/above SLOW_RAMP_END
    private static final float  FAST_VOLUME_MAX = 0.35f;            // fast volume at/above FAST_RAMP_END
    private static final float  COLLIDE_VOLUME = 0.6f;              // one-shot grind-start (lowered from 1.0)
    private static final double SPEED_SMOOTH_RATE = 0.08;           // max per-tick change in audible speed. Server max accel (boosted) ≈ 0.06/tick — anything above that is treated as an abrupt change (snap-to-rail catch-up, network blip) and rate-limited so the sound doesn't tear.

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
        double rampEnd = slowRampEnd();
        if (speed >= rampEnd) return NORMAL_PITCH;
        // Ramp from SLOW_PITCH_START at speed 0 up to NORMAL_PITCH at 50% of maxSpeed().
        float t = (float) Mth.clamp(speed / rampEnd, 0.0, 1.0);
        return Mth.lerp(t, SLOW_PITCH_START, NORMAL_PITCH);
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

        void tick(Player player) {
            // Raw speed = 3D position delta per tick. With noPhysics this matches server-side
            // currentSpeed for the local player, and for remote players it tracks the
            // interpolated position the sound engine is also using for spatialization. A
            // one-tick snap-to-rail catch-up step can briefly spike well past any plausible
            // accel; rate-limit how fast `smoothedSpeed` tracks the raw reading so spikes
            // fade in/out instead of slamming the pitch/volume/loop transitions.
            Vec3 currentPos = player.position();
            double rawSpeed = (prevPos == null) ? 0.0 : currentPos.subtract(prevPos).length();
            prevPos = currentPos;
            double delta = rawSpeed - smoothedSpeed;
            if (Math.abs(delta) > SPEED_SMOOTH_RATE) {
                smoothedSpeed += Math.signum(delta) * SPEED_SMOOTH_RATE;
            } else {
                smoothedSpeed = rawSpeed;
            }
            double speed = smoothedSpeed;

            // Slow loop runs the entire grind. Pitch and volume both ramp up over the first
            // 50% and then hold steady.
            if (slowLoop == null) {
                slowLoop = new GrindLoop(player, ModSounds.GRIND_SLOW_LOOP.get());
                Minecraft.getInstance().getSoundManager().play(slowLoop);
            }
            slowLoop.setPitch(computeSlowPitch(speed));
            slowLoop.setVolume(computeSlowVolume(speed) * userVolume());

            // Fast loop is layered on top of slow at unmodulated pitch. Volume ramps from 0 at
            // 50% up to FAST_VOLUME_MAX at 75%, then holds. Created once when speed first
            // crosses 50% so its pitch is fixed for the lifetime of the instance.
            if (speed >= fastRampStart()) {
                if (fastLoop == null) {
                    fastLoop = new GrindLoop(player, ModSounds.GRIND_FAST_LOOP.get());
                    fastLoop.setPitch(NORMAL_PITCH);
                    Minecraft.getInstance().getSoundManager().play(fastLoop);
                }
                fastLoop.setVolume(computeFastVolume(speed) * userVolume());
            } else if (fastLoop != null) {
                fastLoop.cancel();
                fastLoop = null;
            }
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
