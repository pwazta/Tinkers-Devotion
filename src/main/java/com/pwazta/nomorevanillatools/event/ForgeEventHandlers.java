package com.pwazta.nomorevanillatools.event;

import com.pwazta.nomorevanillatools.ExampleMod;
import com.pwazta.nomorevanillatools.config.MaterialMappingConfig;
import com.pwazta.nomorevanillatools.recipe.RecipeModificationListener;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Forge event handlers for server-side events.
 * Handles recipe reload listening and material config reload.
 */
@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ForgeEventHandlers {

    private static boolean configInitialized = false;

    /**
     * Registers our custom reload listener for recipe modification.
     * This fires whenever server resources are reloaded (server start, /reload command).
     *
     * @param event The AddReloadListenerEvent
     */
    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        // Initialize material mapping config if not already done
        if (!configInitialized) {
            MaterialMappingConfig.initialize(FMLPaths.CONFIGDIR.get().toFile());
            configInitialized = true;
        } else {
            // Reload material mappings when /reload is used
            MaterialMappingConfig.reload();
        }

        // Register our recipe modification listener
        event.addListener(new RecipeModificationListener());
    }
}
