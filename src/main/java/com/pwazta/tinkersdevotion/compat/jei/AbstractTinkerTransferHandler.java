package com.pwazta.tinkersdevotion.compat.jei;

import com.pwazta.tinkersdevotion.network.ModNetwork;
import com.pwazta.tinkersdevotion.network.TransferRecipePacket;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Abstract base class for JEI recipe transfer handlers.
 * Contains all shared matching and transfer logic.
 * Concrete implementations provide container-specific bindings.
 *
 * @param <C> The container menu type this handler supports
 */
public abstract class AbstractTinkerTransferHandler<C extends AbstractContainerMenu>
        implements IRecipeTransferHandler<C, CraftingRecipe> {

    protected final IRecipeTransferHandlerHelper helper;
    protected final CraftingContainerConfig config;

    protected AbstractTinkerTransferHandler(IRecipeTransferHandlerHelper helper, CraftingContainerConfig config) {
        this.helper = helper;
        this.config = config;
    }

    @Override public RecipeType<CraftingRecipe> getRecipeType() { return RecipeTypes.CRAFTING; }

    @Override
    public @Nullable IRecipeTransferError transferRecipe(C container, CraftingRecipe recipe,
            IRecipeSlotsView recipeSlots, Player player, boolean maxTransfer, boolean doTransfer) {

        int recipeWidth = getRecipeWidth(recipe, config);
        int recipeHeight = getRecipeHeight(recipe, config);

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

            int matchingSlot = findMatchingSlot(container, ingredient, config.inventorySlotStart(), inventoryEnd, reservedCounts);

            if (matchingSlot >= 0) {
                transfers.add(new TransferRecipePacket.SlotTransfer(matchingSlot, craftingSlotIndex));
            } else {
                missingSlotIndices.add(i);
            }
        }

        if (!missingSlotIndices.isEmpty()) {
            return createMissingItemsError(helper, recipeSlots, missingSlotIndices);
        }

        if (!doTransfer) return null;

        ModNetwork.CHANNEL.sendToServer(new TransferRecipePacket(transfers, true, config.id()));
        return null;
    }

    // ── Shared static helpers (also used by DynamicCraftingTransferHandler) ──

    /** Finds an inventory slot matching the ingredient. Uses ingredient.test() for TinkerMaterialIngredient support. */
    static int findMatchingSlot(AbstractContainerMenu container, Ingredient ingredient, int startSlot, int endSlot, Map<Integer, Integer> reservedCounts) {
        for (int i = startSlot; i < endSlot; i++) {
            Slot slot = container.slots.get(i);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;

            int reserved = reservedCounts.getOrDefault(i, 0);
            if (stack.getCount() - reserved <= 0) continue;

            if (ingredient.test(stack)) {
                reservedCounts.put(i, reserved + 1);
                return i;
            }
        }
        return -1;
    }

    static int getRecipeWidth(CraftingRecipe recipe, CraftingContainerConfig config) {
        return recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : config.gridWidth();
    }

    /** Calculates rows needed. Shaped: explicit height. Shapeless: ceil(ingredients / gridWidth). */
    static int getRecipeHeight(CraftingRecipe recipe, CraftingContainerConfig config) {
        if (recipe instanceof ShapedRecipe shaped) return shaped.getHeight();
        int count = 0;
        for (Ingredient ing : recipe.getIngredients()) {
            if (!ing.isEmpty()) count++;
        }
        return (int) Math.ceil((double) count / config.gridWidth());
    }

    /** Creates a JEI error highlighting missing ingredient slots. */
    static IRecipeTransferError createMissingItemsError(IRecipeTransferHandlerHelper helper, IRecipeSlotsView recipeSlots, List<Integer> missingIndices) {
        var inputSlots = recipeSlots.getSlotViews(RecipeIngredientRole.INPUT);
        List<IRecipeSlotView> missingSlots = new ArrayList<>();
        for (int idx : missingIndices) {
            if (idx < inputSlots.size()) missingSlots.add(inputSlots.get(idx));
        }
        return helper.createUserErrorForMissingSlots(
                Component.translatable("jei.tooltip.error.recipe.transfer.missing"),
                missingSlots
        );
    }
}
