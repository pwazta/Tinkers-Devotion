package com.pwazta.nomorevanillatools.event;

import com.pwazta.nomorevanillatools.Config;
import com.pwazta.nomorevanillatools.ExampleMod;
import com.pwazta.nomorevanillatools.config.MaterialMappingConfig;
import com.pwazta.nomorevanillatools.recipe.RecipeModificationListener;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Forge event handlers for server-side events.
 * Handles recipe modification when server starts.
 */
@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ForgeEventHandlers {

    private static boolean configInitialized = false;

    /**
     * Modifies recipes when the server is about to start.
     * This fires after recipes are loaded but before the server fully starts.
     *
     * @param event The ServerAboutToStartEvent
     */
    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        // Initialize material mapping config if not already done
        if (!configInitialized) {
            MaterialMappingConfig.initialize(FMLPaths.CONFIGDIR.get().toFile());
            configInitialized = true;
        }

        // Get the server instance from the event
        MinecraftServer server = event.getServer();

        // Modify recipes now that server is available
        RecipeModificationListener.modifyRecipes(server);
    }
}
