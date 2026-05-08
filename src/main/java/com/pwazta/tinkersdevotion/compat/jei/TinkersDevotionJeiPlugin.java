package com.pwazta.tinkersdevotion.compat.jei;

import com.pwazta.tinkersdevotion.TinkersDevotion;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

/**
 * JEI plugin for Tinkers' Devotion.
 * Registers custom recipe transfer handlers that use ingredient.test()
 * for matching, allowing any TC tool with the correct head material to work
 * with the "+" transfer button across multiple crafting containers.
 */
@JeiPlugin
public class TinkersDevotionJeiPlugin implements IModPlugin {

    @Override public ResourceLocation getPluginUid() { return new ResourceLocation(TinkersDevotion.MODID, "jei_plugin"); }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        IRecipeTransferHandlerHelper helper = registration.getTransferHelper();

        // Vanilla Crafting Table (3x3)
        registration.addRecipeTransferHandler(
                new CraftingTableTransferHandler(helper),
                RecipeTypes.CRAFTING
        );

        // Player Inventory Crafting (2x2)
        registration.addRecipeTransferHandler(
                new PlayerInventoryTransferHandler(helper),
                RecipeTypes.CRAFTING
        );

        // Tinkers' Construct Crafting Station (if TC is loaded)
        if (ModList.get().isLoaded("tconstruct")) {
            registerTCHandlers(registration, helper);
        }

        // Dynamic handler for config-defined extra containers (catch-all, low priority)
        // Registered last so specific handlers above take priority
        registration.addRecipeTransferHandler(
                new DynamicCraftingTransferHandler(helper),
                RecipeTypes.CRAFTING
        );
    }

    /** Registers TC-specific handlers. Separate method to avoid class loading issues if TC is not present. */
    private void registerTCHandlers(IRecipeTransferRegistration registration, IRecipeTransferHandlerHelper helper) {
        registration.addRecipeTransferHandler(
                new TCCraftingStationTransferHandler(helper),
                RecipeTypes.CRAFTING
        );
    }
}
