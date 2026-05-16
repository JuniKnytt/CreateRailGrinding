package net.juniknytt.createrailgrinding.network;

import net.juniknytt.createrailgrinding.RailGrind;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = RailGrind.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModNetworking {
    private ModNetworking() {}

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(
            RailGrindSyncPayload.TYPE,
            RailGrindSyncPayload.STREAM_CODEC,
            ClientPayloadHandler::handleSync
        );
        registrar.playToClient(
            RailGrindDebugSyncPayload.TYPE,
            RailGrindDebugSyncPayload.STREAM_CODEC,
            ClientPayloadHandler::handleDebugSync
        );
        registrar.playToClient(
            RailGrindTargetPayload.TYPE,
            RailGrindTargetPayload.STREAM_CODEC,
            ClientPayloadHandler::handleTarget
        );
        registrar.playToClient(
            RailGrindLeanSyncPayload.TYPE,
            RailGrindLeanSyncPayload.STREAM_CODEC,
            ClientPayloadHandler::handleLeanSync
        );
    }
}
