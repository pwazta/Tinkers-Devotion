package com.pwazta.nomorevanillatools.compat.jei;

import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;

import java.util.Optional;

/**
 * JEI recipe transfer handler for Player Inventory screen (2x2 grid).
 * Uses ingredient.test() for TinkerMaterialIngredient matching.
 * Only works for recipes that fit in a 2x2 grid.
 */
public class PlayerInventoryTransferHandler extends AbstractTinkerTransferHandler<InventoryMenu> {

    public PlayerInventoryTransferHandler(IRecipeTransferHandlerHelper helper) {
        super(helper, CraftingContainerConfig.PLAYER_INVENTORY);
    }

    @Override public Class<InventoryMenu> getContainerClass() { return InventoryMenu.class; }

    // InventoryMenu has no MenuType - it's always container ID 0
    @Override public Optional<MenuType<InventoryMenu>> getMenuType() { return Optional.empty(); }
}
