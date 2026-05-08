package com.pwazta.tinkersdevotion.compat.jei;

import com.pwazta.tinkersdevotion.network.ModNetwork;
import com.pwazta.tinkersdevotion.network.TransferRecipePacket;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Dynamic JEI recipe transfer handler for config-defined extra containers.
 * Uses AbstractContainerMenu as catch-all (lower priority than specific handlers).
 * Looks up container config at runtime from CraftingContainerRegistry.
 */
public class DynamicCraftingTransferHandler implements IRecipeTransferHandler<AbstractContainerMenu, CraftingRecipe> {

    private final IRecipeTransferHandlerHelper helper;

    public DynamicCraftingTransferHandler(IRecipeTransferHandlerHelper helper) {
        this.helper = helper;
    }

    @Override public Class<? extends AbstractContainerMenu> getContainerClass() { return AbstractContainerMenu.class; }
    @Override public Optional<MenuType<AbstractContainerMenu>> getMenuType() { return Optional.empty(); }
    @Override public RecipeType<CraftingRecipe> getRecipeType() { return RecipeTypes.CRAFTING; }

    @Override
    public @Nullable IRecipeTransferError transferRecipe(AbstractContainerMenu container, CraftingRecipe recipe,
            IRecipeSlotsView recipeSlots, Player player, boolean maxTransfer, boolean doTransfer) {

        Optional<CraftingContainerConfig> configOpt = CraftingContainerRegistry.findForContainer(container);
        if (configOpt.isEmpty()) return null;

        CraftingContainerConfig config = configOpt.get();

        int recipeWidth = AbstractTinkerTransferHandler.getRecipeWidth(recipe, config);
        int recipeHeight = AbstractTinkerTransferHandler.getRecipeHeight(recipe, config);

        if (!config.canFitRecipe(recipeWidth, recipeHeight)) {
            String errorKey = recipe instanceof ShapedRecipe
                    ? "jei.tooltip.error.recipe.transfer.too.large.shaped"
                    : "jei.tooltip.error.recipe.transfer.too.large.shapeless";
            return helper.createUserErrorWithTooltip(Component.translatable(errorKey));
        }

        var ingredients = recipe.getIngredients();
        List<TransferRecipePacket.SlotTransfer> transfers = new ArrayList<>();
        Map<Integer, Integer> reservedCounts = new HashMap<>();
        List<Integer> missingSlotIndices = new ArrayList<>();

        int inventoryEnd = config.getInventorySlotEnd(container.slots.size());

        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            if (ingredient.isEmpty()) continue;

            int craftingSlotIndex = config.getCraftingSlotIndex(i, recipeWidth);
            if (!config.isValidCraftingSlot(craftingSlotIndex)) continue;

            int matchingSlot = AbstractTinkerTransferHandler.findMatchingSlot(container, ingredient, config.inventorySlotStart(), inventoryEnd, reservedCounts);

            if (matchingSlot >= 0) {
                transfers.add(new TransferRecipePacket.SlotTransfer(matchingSlot, craftingSlotIndex));
            } else {
                missingSlotIndices.add(i);
            }
        }

        if (!missingSlotIndices.isEmpty()) {
            return AbstractTinkerTransferHandler.createMissingItemsError(helper, recipeSlots, missingSlotIndices);
        }

        if (!doTransfer) return null;

        ModNetwork.CHANNEL.sendToServer(new TransferRecipePacket(transfers, true, config.id()));
        return null;
    }
}
