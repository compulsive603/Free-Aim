package com.example.freeaim;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

public final class FreeAimNetwork {
    private static final String PROTOCOL = "1";

    /** "Accept missing": a client with the mod can still join a server without it (parry just won't work there). */
    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(FreeAimMod.MODID, "main"))
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL))
            .serverAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL))
            .simpleChannel();

    public static void init() {
        CHANNEL.registerMessage(0, HandStatePacket.class, HandStatePacket::encode, HandStatePacket::decode,
                HandStatePacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    private FreeAimNetwork() {}
}
