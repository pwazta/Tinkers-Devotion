package com.pwazta.nomorevanillatools.compat.jei;

import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.world.inventory.MenuType;
import slimeknights.tconstruct.tables.menu.CraftingStationContainerMenu;

import java.util.Optional;

/**
 * JEI recipe transfer handler for Tinkers' Construct Crafting Station (3x3 grid).
 * Uses ingredient.test() for TinkerMaterialIngredient matching.
 *
 * Note: This class has a hard dependency on Tinkers' Construct.
 * Only load/register this handler if TC is present (check ModList first).
 */
public class TCCraftingStationTransferHandler extends AbstractTinkerTransferHandler<CraftingStationContainerMenu> {

    public TCCraftingStationTransferHandler(IRecipeTransferHandlerHelper helper) {
        super(helper, CraftingContainerConfig.TC_CRAFTING_STATION);
    }

    @Override
    public Class<CraftingStationContainerMenu> getContainerClass() {
        return CraftingStationContainerMenu.class;
    }

    @Override
    public Optional<MenuType<CraftingStationContainerMenu>> getMenuType() {
        // Return empty - JEI will match via getContainerClass() instead
        // This avoids hard dependency on TC's registry field names
        return Optional.empty();
    }
}
