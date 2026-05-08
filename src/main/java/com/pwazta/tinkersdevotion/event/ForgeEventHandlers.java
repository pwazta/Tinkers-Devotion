package com.pwazta.tinkersdevotion.event;

import com.mojang.logging.LogUtils;
import com.pwazta.tinkersdevotion.TinkersDevotion;
import com.pwazta.tinkersdevotion.command.GenerateRecipesCommand;
import com.pwazta.tinkersdevotion.config.TiersToTcMaterials;
import com.pwazta.tinkersdevotion.datagen.DatapackHelper;
import com.pwazta.tinkersdevotion.loot.TinkerToolBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import slimeknights.tconstruct.library.events.MaterialsLoadedEvent;

/**
 * Forge event handlers for server-side events.
 * Rebuilds tool tier caches on materials load and auto-generates recipes on first world load.
 */
@Mod.EventBusSubscriber(modid = TinkersDevotion.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ForgeEventHandlers {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Set to true when recipes are auto-generated during ServerAboutToStartEvent, so we can reload on ServerStartedEvent. */
    private static boolean needsReloadAfterStart = false;

    /**
     * Rebuilds tool tier caches from the TC registry and clears loot/ingredient caches.
     * Fires on both client and server after TC materials sync/reload.
     */
    @SubscribeEvent
    public static void onMaterialsLoaded(MaterialsLoadedEvent event) {
        TiersToTcMaterials.rebuildToolCaches();
        TinkerToolBuilder.clearCaches();
    }

    /**
     * Auto-generates replacement recipes on first world load.
     * Material mappings are already populated by onMaterialsLoaded (fires before this event).
     * Datapacks are already loaded at this point, so generated recipes need a reload to take effect.
     */
    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        needsReloadAfterStart = false;
        MinecraftServer server = event.getServer();

        if (!DatapackHelper.isGenerated(server)) {
            LOGGER.info("First launch detected - auto-generating recipes...");
            try {
                int count = GenerateRecipesCommand.generate(server);
                LOGGER.info("Auto-generation complete! Generated {} recipes.", count);
                LOGGER.info("Recipes saved to: world/datapacks/tinkersdevotion_generated/");
                needsReloadAfterStart = true;
            } catch (Exception e) {
                LOGGER.error("Failed to auto-generate recipes", e);
            }
        }
    }

    /**
     * Dispatches Mantle's vanilla-recipe disable (overworld must exist; not yet at ServerAboutToStart),
     * then reloads datapacks so both our generated pack and Mantle's SlimeKnightsGenerated pack take effect.
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (needsReloadAfterStart) {
            needsReloadAfterStart = false;
            MinecraftServer server = event.getServer();
            int disabled = GenerateRecipesCommand.disableVanillaRecipes(server);
            LOGGER.info("Mantle disabled {} vanilla recipes", disabled);
            LOGGER.info("Reloading datapacks to activate auto-generated recipes...");
            DatapackHelper.discoverAndReload(server).exceptionally(e -> {
                LOGGER.error("Failed to reload datapacks after auto-generation", e);
                return null;
            });
        }
    }
}
