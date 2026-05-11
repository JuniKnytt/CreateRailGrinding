package net.juniknytt.createrailgrinding.ponder;

import net.createmod.ponder.foundation.PonderIndex;
import net.juniknytt.createrailgrinding.RailGrind;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = RailGrind.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class RailGrindPonderRegistration {

    private RailGrindPonderRegistration() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        PonderIndex.addPlugin(new RailGrindPonderPlugin());
    }
}
