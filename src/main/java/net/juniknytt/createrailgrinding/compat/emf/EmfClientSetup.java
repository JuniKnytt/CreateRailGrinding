package net.juniknytt.createrailgrinding.compat.emf;

import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.compat.Mods;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = RailGrind.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class EmfClientSetup {

    private EmfClientSetup() {}

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        if (Mods.EMF.isLoaded()) {
            event.enqueueWork(RailGrindEmfCompat::init);
        }
    }
}
