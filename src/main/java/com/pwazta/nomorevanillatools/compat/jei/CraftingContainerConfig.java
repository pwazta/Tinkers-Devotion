package com.pwazta.nomorevanillatools.compat.jei;

import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * Configuration record for crafting container slot layouts.
 * Uses record instead of enum to allow future config-file-based instantiation.
 *
 * @param id Unique identifier (e.g., "crafting_table", "player_inventory")
 * @param containerClassName Full class name for reflection-based matching
 * @param gridWidth Width of crafting grid (2 for 2x2, 3 for 3x3)
 * @param gridHeight Height of crafting grid
 * @param craftingSlotStart First crafting grid slot index (inclusive)
 * @param craftingSlotEnd Last crafting grid slot index (exclusive)
 * @param inventorySlotStart First inventory slot index (inclusive)
 * @param inventorySlotEnd Last inventory slot index (exclusive), -1 for dynamic (container.slots.size())
 */
public record CraftingContainerConfig(
        String id,
        String containerClassName,
        int gridWidth,
        int gridHeight,
        int craftingSlotStart,
        int craftingSlotEnd,
        int inventorySlotStart,
        int inventorySlotEnd
) {
    // Built-in configurations for vanilla and TC containers

    /**
     * Vanilla Crafting Table (3x3 grid)
     * Slots: 0=output, 1-9=crafting grid, 10-45=inventory+hotbar
     */
    public static final CraftingContainerConfig CRAFTING_TABLE = new CraftingContainerConfig(
            "crafting_table",
            "net.minecraft.world.inventory.CraftingMenu",
            3, 3,  // 3x3 grid
            1, 10, // crafting slots 1-9
            10, 46 // inventory slots 10-45
    );

    /**
     * Player Inventory (2x2 grid)
     * Slots: 0=output, 1-4=crafting grid, 5-8=armor, 9-35=inventory, 36-44=hotbar, 45=offhand
     */
    public static final CraftingContainerConfig PLAYER_INVENTORY = new CraftingContainerConfig(
            "player_inventory",
            "net.minecraft.world.inventory.InventoryMenu",
            2, 2,  // 2x2 grid
            1, 5,  // crafting slots 1-4
            9, 45  // inventory slots 9-44 (skip armor 5-8, exclude offhand 45)
    );

    /**
     * Tinkers' Construct Crafting Station (3x3 grid, same layout as vanilla)
     * Has additional side inventory slots from adjacent chests
     */
    public static final CraftingContainerConfig TC_CRAFTING_STATION = new CraftingContainerConfig(
            "tc_crafting_station",
            "slimeknights.tconstruct.tables.menu.CraftingStationContainerMenu",
            3, 3,  // 3x3 grid
            1, 10, // crafting slots 1-9
            10, -1 // inventory starts at 10, -1 = use container.slots.size() (dynamic for side inventory)
    );

    /** Maps a recipe ingredient index to this container's crafting slot, accounting for grid width. */
    public int getCraftingSlotIndex(int recipeIndex, int recipeWidth) {
        int recipeRow = recipeIndex / recipeWidth;
        int recipeCol = recipeIndex % recipeWidth;
        return craftingSlotStart + (recipeRow * gridWidth) + recipeCol;
    }

    /** Checks if a recipe fits in this container's crafting grid. */
    public boolean canFitRecipe(int recipeWidth, int recipeHeight) {
        return recipeWidth <= gridWidth && recipeHeight <= gridHeight;
    }

    /** Validates that a slot index is within the crafting grid range. */
    public boolean isValidCraftingSlot(int slotIndex) { return slotIndex >= craftingSlotStart && slotIndex < craftingSlotEnd; }

    /** Gets the actual inventory end slot, handling dynamic (-1) case. */
    public int getInventorySlotEnd(int containerSize) { return inventorySlotEnd == -1 ? containerSize : inventorySlotEnd; }

    /** Checks if the given container matches this configuration via class name comparison. */
    public boolean matchesContainer(AbstractContainerMenu container) {
        if (container == null) return false;
        return container.getClass().getName().equals(containerClassName);
    }

    public int getCraftingSlotCount() { return gridWidth * gridHeight; }
}
