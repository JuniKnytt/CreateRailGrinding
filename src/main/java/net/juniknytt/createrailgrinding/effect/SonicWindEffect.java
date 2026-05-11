package net.juniknytt.createrailgrinding.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

// Subclass exists only to expose MobEffect's protected constructor across packages.
public class SonicWindEffect extends MobEffect {
    public SonicWindEffect() {
        super(MobEffectCategory.BENEFICIAL, 0x7CAFC6);
    }
}
