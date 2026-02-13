package com.pwazta.nomorevanillatools.event;

import com.mojang.logging.LogUtils;
import com.pwazta.nomorevanillatools.Config;
import com.pwazta.nomorevanillatools.ExampleMod;
import com.pwazta.nomorevanillatools.command.GenerateRecipesCommand;
import com.pwazta.nomorevanillatools.config.MaterialMappingConfig;
import com.pwazta.nomorevanillatools.datagen.DatapackHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import slimeknights.tconstruct.library.events.MaterialsLoadedEvent;

/**
 * Forge event handlers for server-side events.
 * Handles material config auto-generation and optional recipe auto-generation.
 */
@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ForgeEventHandlers {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Set to true when recipes are auto-generated during ServerAboutToStartEvent, so we can reload on ServerStartedEvent. */
    private static boolean needsReloadAfterStart = false;

    /**
     * Auto-generates material mappings when TC materials are loaded.
     * Fires on both client and server — after datapack reload (server) or after sync (client).
     * This is Phase 2 of MaterialMappingConfig initialization.
     */
    @SubscribeEvent
    public static void onMaterialsLoaded(MaterialsLoadedEvent event) {
        MaterialMappingConfig.generateIfNeeded(Config.forceRegenerateMaterialConfig);
    }

    /**
     * Optionally auto-generates replacement recipes when the server is about to start.
     * Material mappings are already populated by onMaterialsLoaded (fires before this event).
     * Datapacks are already loaded at this point, so generated recipes need a reload to take effect.
     */
    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        needsReloadAfterStart = false;
        if (Config.autoGenerateRecipes) {
            MinecraftServer server = event.getServer();

            if (!DatapackHelper.isGenerated(server)) {
                LOGGER.info("First launch detected - auto-generating recipes...");
                try {
                    int count = GenerateRecipesCommand.generate(server);
                    LOGGER.info("Auto-generation complete! Generated {} recipes.", count);
                    LOGGER.info("Recipes saved to: world/datapacks/nomorevanillatools_generated/");
                    needsReloadAfterStart = true;
                } catch (Exception e) {
                    LOGGER.error("Failed to auto-generate recipes", e);
                }
            }
        }
    }

    /**
     * Reloads datapacks after server start if recipes were just auto-generated.
     * This fixes the P1 issue where generated recipes aren't active until manual /reload.
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (needsReloadAfterStart) {
            needsReloadAfterStart = false;
            MinecraftServer server = event.getServer();
            LOGGER.info("Reloading datapacks to activate auto-generated recipes...");
            server.reloadResources(server.getPackRepository().getSelectedIds()).exceptionally(e -> {
                LOGGER.error("Failed to reload datapacks after auto-generation", e);
                return null;
            });
        }
    }
}
