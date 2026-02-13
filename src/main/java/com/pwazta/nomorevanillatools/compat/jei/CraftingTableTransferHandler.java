package com.pwazta.nomorevanillatools.compat.jei;

import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.MenuType;

import java.util.Optional;

/**
 * JEI recipe transfer handler for vanilla Crafting Table (3x3 grid).
 * Uses ingredient.test() for TinkerMaterialIngredient matching.
 */
public class CraftingTableTransferHandler extends AbstractTinkerTransferHandler<CraftingMenu> {

    public CraftingTableTransferHandler(IRecipeTransferHandlerHelper helper) {
        super(helper, CraftingContainerConfig.CRAFTING_TABLE);
    }

    @Override public Class<CraftingMenu> getContainerClass() { return CraftingMenu.class; }
    @Override public Optional<MenuType<CraftingMenu>> getMenuType() { return Optional.of(MenuType.CRAFTING); }
}
