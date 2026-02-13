package com.pwazta.nomorevanillatools.compat.jei;

import com.pwazta.nomorevanillatools.network.ModNetwork;
import com.pwazta.nomorevanillatools.network.TransferRecipePacket;
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
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
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

    // Catch-all (lower priority than specific handlers), match by class only
    @Override public Class<? extends AbstractContainerMenu> getContainerClass() { return AbstractContainerMenu.class; }
    @Override public Optional<MenuType<AbstractContainerMenu>> getMenuType() { return Optional.empty(); }
    @Override public RecipeType<CraftingRecipe> getRecipeType() { return RecipeTypes.CRAFTING; }

    @Override
    public @Nullable IRecipeTransferError transferRecipe(AbstractContainerMenu container, CraftingRecipe recipe,
            IRecipeSlotsView recipeSlots, Player player, boolean maxTransfer, boolean doTransfer) {

        // Look up container config at runtime
        Optional<CraftingContainerConfig> configOpt = CraftingContainerRegistry.findForContainer(container);

        if (configOpt.isEmpty()) {
            // Container not configured - silent no-op
            // This allows the "+" button to appear but not transfer
            // Users can add the container to config if they want support
            return null;
        }

        CraftingContainerConfig config = configOpt.get();

        // Get recipe dimensions
        int recipeWidth = getRecipeWidth(recipe, config);
        int recipeHeight = getRecipeHeight(recipe, config);

        // Check if recipe fits in this container's grid
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

            int matchingSlot = findMatchingSlot(
                    container,
                    ingredient,
                    config.inventorySlotStart(),
                    inventoryEnd,
                    reservedCounts
            );

            if (matchingSlot >= 0) {
                transfers.add(new TransferRecipePacket.SlotTransfer(matchingSlot, craftingSlotIndex));
            } else {
                missingSlotIndices.add(i);
            }
        }

        if (!missingSlotIndices.isEmpty()) {
            return createMissingItemsError(recipeSlots, missingSlotIndices);
        }

        if (!doTransfer) {
            return null; // Success check only
        }

        // Send packet with config ID for server validation
        ModNetwork.CHANNEL.sendToServer(new TransferRecipePacket(transfers, true, config.id()));

        return null; // Success
    }

    /**
     * Finds an inventory slot containing an item matching the ingredient.
     * Uses ingredient.test() for TinkerMaterialIngredient compatibility.
     */
    private int findMatchingSlot(AbstractContainerMenu container, Ingredient ingredient,
            int startSlot, int endSlot, Map<Integer, Integer> reservedCounts) {

        for (int i = startSlot; i < endSlot; i++) {
            Slot slot = container.slots.get(i);
            ItemStack stack = slot.getItem();

            if (stack.isEmpty()) continue;

            int reserved = reservedCounts.getOrDefault(i, 0);
            int available = stack.getCount() - reserved;
            if (available <= 0) continue;

            if (ingredient.test(stack)) {
                reservedCounts.put(i, reserved + 1);
                return i;
            }
        }
        return -1;
    }

    private int getRecipeWidth(CraftingRecipe recipe, CraftingContainerConfig config) {
        if (recipe instanceof ShapedRecipe shaped) {
            return shaped.getWidth();
        }
        return config.gridWidth();
    }

    private int getRecipeHeight(CraftingRecipe recipe, CraftingContainerConfig config) {
        if (recipe instanceof ShapedRecipe shaped) {
            return shaped.getHeight();
        }
        int ingredientCount = 0;
        for (Ingredient ing : recipe.getIngredients()) {
            if (!ing.isEmpty()) ingredientCount++;
        }
        return (int) Math.ceil((double) ingredientCount / config.gridWidth());
    }

    private IRecipeTransferError createMissingItemsError(IRecipeSlotsView recipeSlots, List<Integer> missingIndices) {
        var inputSlots = recipeSlots.getSlotViews(RecipeIngredientRole.INPUT);
        List<IRecipeSlotView> missingSlots = new ArrayList<>();
        for (int idx : missingIndices) {
            if (idx < inputSlots.size()) {
                missingSlots.add(inputSlots.get(idx));
            }
        }
        return helper.createUserErrorForMissingSlots(
                Component.translatable("jei.tooltip.error.recipe.transfer.missing"),
                missingSlots
        );
    }
}
