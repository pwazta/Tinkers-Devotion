package com.pwazta.nomorevanillatools.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * Handles network registration for the mod.
 * Used for JEI recipe transfer functionality.
 */
public class ModNetwork {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("nomorevanillatools", "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    /**
     * Registers all network packets. Call during mod initialization.
     */
    public static void register() {
        CHANNEL.registerMessage(
                packetId++,
                TransferRecipePacket.class,
                TransferRecipePacket::encode,
                TransferRecipePacket::decode,
                TransferRecipePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
    }
}
