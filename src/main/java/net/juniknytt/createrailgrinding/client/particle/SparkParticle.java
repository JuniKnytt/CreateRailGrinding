package net.juniknytt.createrailgrinding.client.particle;

import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.particle.ModParticles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import org.jetbrains.annotations.Nullable;

public class SparkParticle extends TextureSheetParticle {

    private static final float  SIZE_BASE              = 0.25f;
    private static final double SPEED_SCALE_SATURATION = 0.25;

    SparkParticle(ClientLevel level, double x, double y, double z,
                  double dx, double dy, double dz, SpriteSet sprites) {
        super(level, x, y, z);

        this.xd = dx;
        this.yd = dy;
        this.zd = dz;

        this.gravity = 0.75f;
        this.friction = 1.00f;
        this.hasPhysics = true;
        this.lifetime = 14 + this.random.nextInt(8);

        double horizontalSpeed = Math.sqrt(dx * dx + dz * dz);
        float speedScale = (float) Math.min(1.0, horizontalSpeed / SPEED_SCALE_SATURATION);
        this.quadSize *= SIZE_BASE * speedScale;

        this.setSpriteFromAge(sprites);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.removed) {
            this.alpha = Math.max(0.0f, 1.0f - (float) this.age / (float) this.lifetime);
        }
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Nullable
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double dx, double dy, double dz) {
            return new SparkParticle(level, x, y, z, dx, dy, dz, this.sprites);
        }
    }

    @EventBusSubscriber(modid = RailGrind.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ClientRegistration {
        private ClientRegistration() {}

        @SubscribeEvent
        public static void onRegisterParticleProviders(RegisterParticleProvidersEvent event) {
            event.registerSpriteSet(ModParticles.SPARK.get(), Provider::new);
        }
    }
}
