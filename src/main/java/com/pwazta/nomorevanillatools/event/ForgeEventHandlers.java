package com.pwazta.nomorevanillatools.event;

import com.mojang.logging.LogUtils;
import com.pwazta.nomorevanillatools.Config;
import com.pwazta.nomorevanillatools.ExampleMod;
import com.pwazta.nomorevanillatools.command.GenerateRecipesCommand;
import com.pwazta.nomorevanillatools.datagen.DatapackHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Forge event handlers for server-side events.
 * Handles optional auto-generation of replacement recipes when the server starts.
 */
@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ForgeEventHandlers {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Optionally auto-generates recipes when the server is about to start.
     * MaterialMappingConfig is already initialized in ExampleMod.commonSetup().
     *
     * @param event The ServerAboutToStartEvent
     */
    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
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
