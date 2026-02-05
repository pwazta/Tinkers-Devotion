package com.pwazta.nomorevanillatools.compat.jei;

import com.pwazta.nomorevanillatools.network.ModNetwork;
import com.pwazta.nomorevanillatools.network.TransferRecipePacket;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Custom JEI recipe transfer handler that uses ingredient.test() for matching.
 * This allows any TC tool with the correct head material to work with the "+" button,
 * not just the variants explicitly listed in getItems().
 */
public class TinkerToolTransferHandler implements IRecipeTransferHandler<CraftingMenu, CraftingRecipe> {

    private final IRecipeTransferHandlerHelper helper;

    public TinkerToolTransferHandler(IRecipeTransferHandlerHelper helper) {
        this.helper = helper;
    }

    @Override
    public Class<CraftingMenu> getContainerClass() {
        return CraftingMenu.class;
    }

    @Override
    public Optional<MenuType<CraftingMenu>> getMenuType() {
        return Optional.of(MenuType.CRAFTING);
    }

    @Override
    public RecipeType<CraftingRecipe> getRecipeType() {
        return RecipeTypes.CRAFTING;
    }

    @Override
    public @Nullable IRecipeTransferError transferRecipe(
            CraftingMenu container,
            CraftingRecipe recipe,
            IRecipeSlotsView recipeSlots,
            Player player,
            boolean maxTransfer,
            boolean doTransfer) {

        // Get the recipe's ingredients
        var ingredients = recipe.getIngredients();

        // Build mapping: which inventory slot can satisfy which recipe slot
        // Recipe slots for crafting table are 1-9 (0 is output)
        // Player inventory in CraftingMenu starts at slot 10

        List<TransferRecipePacket.SlotTransfer> transfers = new ArrayList<>();
        Map<Integer, Integer> reservedCounts = new HashMap<>(); // Track how many items reserved per slot
        List<Integer> missingSlotIndices = new ArrayList<>();

        // Crafting grid slots in CraftingMenu are indices 1-9
        int craftingSlotStart = 1;
        int gridWidth = 3; // 3x3 crafting table

        // Player inventory slots start after crafting slots
        // CraftingMenu: 0=output, 1-9=crafting, 10-36=inventory, 37-45=hotbar
        int inventorySlotStart = 10;
        int inventorySlotEnd = container.slots.size();

        // Get recipe dimensions for proper slot mapping
        int recipeWidth = 3; // default for shapeless or 3-wide
        if (recipe instanceof ShapedRecipe shaped) {
            recipeWidth = shaped.getWidth();
        }

        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);

            // Skip empty ingredients (empty slots in recipe)
            if (ingredient.isEmpty()) {
                continue;
            }

            // Calculate correct slot position based on recipe dimensions
            // For shaped recipes, map (row, col) in recipe to (row, col) in 3x3 grid
            int recipeRow = i / recipeWidth;
            int recipeCol = i % recipeWidth;
            int craftingSlotIndex = craftingSlotStart + (recipeRow * gridWidth) + recipeCol;

            if (craftingSlotIndex < craftingSlotStart || craftingSlotIndex > 9) {
                continue; // Safety check
            }

            // Find a matching item in player inventory using ingredient.test()
            int matchingInventorySlot = findMatchingSlot(
                    container,
                    ingredient,
                    inventorySlotStart,
                    inventorySlotEnd,
                    reservedCounts
            );

            if (matchingInventorySlot >= 0) {
                // reservedCounts already updated inside findMatchingSlot
                transfers.add(new TransferRecipePacket.SlotTransfer(matchingInventorySlot, craftingSlotIndex));
            } else {
                // No matching item found for this ingredient
                missingSlotIndices.add(i);
            }
        }

        // If there are missing ingredients, return an error
        if (!missingSlotIndices.isEmpty()) {
            // Convert slot indices to slot views for highlighting
            var inputSlots = recipeSlots.getSlotViews(RecipeIngredientRole.INPUT);
            List<mezz.jei.api.gui.ingredient.IRecipeSlotView> missingSlots = new ArrayList<>();
            for (int idx : missingSlotIndices) {
                if (idx < inputSlots.size()) {
                    missingSlots.add(inputSlots.get(idx));
                }
            }
            return helper.createUserErrorForMissingSlots(
                    Component.translatable("jei.tooltip.error.recipe.transfer.missing"),
                    missingSlots
            );
        }

        // If we're just checking (not transferring), return success
        if (!doTransfer) {
            return null; // null = success
        }

        // Send packet to server to perform the actual transfer
        ModNetwork.CHANNEL.sendToServer(new TransferRecipePacket(transfers, true));

        return null; // Success
    }

    /**
     * Finds an inventory slot containing an item that matches the ingredient.
     * Tracks reserved counts to allow multiple items from the same stack.
     *
     * @param container The crafting menu
     * @param ingredient The ingredient to match
     * @param startSlot First inventory slot to check
     * @param endSlot Last inventory slot (exclusive)
     * @param reservedCounts Map tracking how many items have been reserved from each slot
     * @return The slot index, or -1 if not found
     */
    private int findMatchingSlot(
            CraftingMenu container,
            Ingredient ingredient,
            int startSlot,
            int endSlot,
            Map<Integer, Integer> reservedCounts) {

        for (int i = startSlot; i < endSlot; i++) {
            Slot slot = container.slots.get(i);
            ItemStack stack = slot.getItem();

            if (stack.isEmpty()) {
                continue;
            }

            // Check if there are still available items in this slot
            int reserved = reservedCounts.getOrDefault(i, 0);
            int available = stack.getCount() - reserved;
            if (available <= 0) {
                continue;
            }

            // Use ingredient.test() - this is the key!
            // This calls our TinkerMaterialIngredient.test() which checks head material
            if (ingredient.test(stack)) {
                reservedCounts.put(i, reserved + 1);
                return i;
            }
        }

        return -1; // Not found
    }
}
