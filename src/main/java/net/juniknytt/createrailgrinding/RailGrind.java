package net.juniknytt.createrailgrinding;

import com.mojang.logging.LogUtils;
import net.juniknytt.createrailgrinding.advancement.ModTriggers;
import net.juniknytt.createrailgrinding.effect.ModEffects;
import net.juniknytt.createrailgrinding.enchantment.ModEnchantments;
import net.juniknytt.createrailgrinding.network.ModNetworking;
import net.juniknytt.createrailgrinding.particle.ModParticles;
import net.juniknytt.createrailgrinding.sound.ModSounds;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(RailGrind.MODID)
public class RailGrind {

    public static final String MODID = "createrailgrinding";

    public static final Logger LOGGER = LogUtils.getLogger();

    public RailGrind() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModSounds.register(modBus);
        ModEffects.register(modBus);
        ModEnchantments.register(modBus);
        ModParticles.register(modBus);
        ModNetworking.register();
        modBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(ModTriggers::register);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Rolling around at the speed of sound!");
    }
}
