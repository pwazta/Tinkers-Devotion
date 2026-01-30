package com.pwazta.nomorevanillatools.event;

import com.mojang.logging.LogUtils;
import com.pwazta.nomorevanillatools.Config;
import com.pwazta.nomorevanillatools.ExampleMod;
import com.pwazta.nomorevanillatools.command.GenerateRecipesCommand;
import com.pwazta.nomorevanillatools.config.MaterialMappingConfig;
import com.pwazta.nomorevanillatools.datagen.DatapackHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

/**
 * Forge event handlers for server-side events.
 * Handles configuration initialization and optional auto-generation when server starts.
 */
@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ForgeEventHandlers {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean configInitialized = false;

    /**
     * Initializes configuration and optionally auto-generates recipes when the server is about to start.
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

        // Check if auto-generation is enabled
        if (Config.autoGenerateRecipes) {
            MinecraftServer server = event.getServer();

            // Check if recipes have already been generated
            if (!DatapackHelper.isGenerated(server)) {
                LOGGER.info("First launch detected - auto-generating recipes...");
                try {
                    int count = GenerateRecipesCommand.generate(server);
                    LOGGER.info("Auto-generation complete! Generated {} recipes.", count);
                    LOGGER.info("Recipes saved to: world/datapacks/nomorevanillatools_generated/");
                    LOGGER.info("Run /reload to apply the changes, or restart the server.");
                } catch (Exception e) {
                    LOGGER.error("Failed to auto-generate recipes", e);
                }
            }
        }
    }
}
