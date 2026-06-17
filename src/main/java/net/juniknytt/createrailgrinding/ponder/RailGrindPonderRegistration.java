package net.juniknytt.createrailgrinding.ponder;

import net.createmod.ponder.foundation.PonderIndex;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = RailGrind.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class RailGrindPonderRegistration {

    private RailGrindPonderRegistration() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        PonderIndex.addPlugin(new RailGrindPonderPlugin());
    }
}
