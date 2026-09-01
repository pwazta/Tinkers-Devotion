package com.pwazta.tinkersdevotion.event;

import com.mojang.logging.LogUtils;
import com.pwazta.tinkersdevotion.TinkersDevotion;
import com.pwazta.tinkersdevotion.command.GenerateRecipesCommand;
import com.pwazta.tinkersdevotion.config.TiersToTcMaterials;
import com.pwazta.tinkersdevotion.datagen.DatapackHelper;
import com.pwazta.tinkersdevotion.loot.TinkerToolBuilder;
import net.minecraft.server.MinecraftServer;
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
     * Regenerates when the stored hash is stale. Runs at ServerStarted rather than
     * ServerAboutToStart because generation now reloads datapacks itself, and Mantle's dispatch
     * needs the overworld.
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        String storedHash = DatapackHelper.readStoredHash(server);
        if (DatapackHelper.computeHash(server).equals(storedHash)) return;

        LOGGER.info(storedHash == null
            ? "No generated datapack found, auto-generating recipes..."
            : "Mod set, exclusions or vanilla-crafting toggle changed, regenerating recipes...");
        try {
            LOGGER.info("Auto-generation complete! Generated {} recipes.", GenerateRecipesCommand.generate(server));
        } catch (Exception e) {
            LOGGER.error("Failed to auto-generate recipes, will retry next launch", e);
        }
    }
}
