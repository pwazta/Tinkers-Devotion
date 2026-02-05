package com.pwazta.nomorevanillatools.compat.jei;

import com.pwazta.nomorevanillatools.ExampleMod;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import net.minecraft.resources.ResourceLocation;

/**
 * JEI plugin for No More Vanilla Tools.
 * Registers a custom recipe transfer handler that uses ingredient.test()
 * for matching, allowing any TC tool with the correct head material to work
 * with the "+" transfer button.
 */
@JeiPlugin
public class NoMoreVanillaToolsJeiPlugin implements IModPlugin {

    @Override
    public ResourceLocation getPluginUid() {
        return new ResourceLocation(ExampleMod.MODID, "jei_plugin");
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        registration.addRecipeTransferHandler(
                new TinkerToolTransferHandler(registration.getTransferHelper()),
                RecipeTypes.CRAFTING
        );
    }
}
