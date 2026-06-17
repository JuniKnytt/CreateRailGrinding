package net.juniknytt.createrailgrinding.effect;

import net.juniknytt.createrailgrinding.client.SonicWindClientExtensions;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraftforge.client.extensions.common.IClientMobEffectExtensions;

import java.util.function.Consumer;

public class SonicWindEffect extends MobEffect {
    public SonicWindEffect() {
        super(MobEffectCategory.BENEFICIAL, 0x7CAFC6);
    }

    @Override
    public void initializeClient(Consumer<IClientMobEffectExtensions> consumer) {
        consumer.accept(new SonicWindClientExtensions());
    }
}
