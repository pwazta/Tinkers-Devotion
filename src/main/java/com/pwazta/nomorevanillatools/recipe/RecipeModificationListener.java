package com.pwazta.nomorevanillatools.recipe;

import com.mojang.logging.LogUtils;
import com.pwazta.nomorevanillatools.Config;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Runtime recipe modification listener that replaces vanilla tools with Tinker's Construct equivalents.
 * Implements PreparableReloadListener to hook into recipe loading.
 */
public class RecipeModificationListener implements PreparableReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Map of vanilla tool items to their material tier
    private static final Map<Item, String> VANILLA_TOOL_TO_TIER = new HashMap<>();
    // Map of vanilla tool items to their tool type
    private static final Map<Item, String> VANILLA_TOOL_TO_TYPE = new HashMap<>();

    // Set of vanilla tool recipe IDs to remove
    private static final Set<ResourceLocation> VANILLA_TOOL_RECIPES_TO_REMOVE = new HashSet<>();

    static {
        // Initialize vanilla tool mappings
        // Wooden tools
        registerVanillaTool(Items.WOODEN_SWORD, "wooden", "sword");
        registerVanillaTool(Items.WOODEN_PICKAXE, "wooden", "pickaxe");
        registerVanillaTool(Items.WOODEN_AXE, "wooden", "axe");
        registerVanillaTool(Items.WOODEN_SHOVEL, "wooden", "shovel");
        registerVanillaTool(Items.WOODEN_HOE, "wooden", "hoe");

        // Stone tools
        registerVanillaTool(Items.STONE_SWORD, "stone", "sword");
        registerVanillaTool(Items.STONE_PICKAXE, "stone", "pickaxe");
        registerVanillaTool(Items.STONE_AXE, "stone", "axe");
        registerVanillaTool(Items.STONE_SHOVEL, "stone", "shovel");
        registerVanillaTool(Items.STONE_HOE, "stone", "hoe");

        // Iron tools
        registerVanillaTool(Items.IRON_SWORD, "iron", "sword");
        registerVanillaTool(Items.IRON_PICKAXE, "iron", "pickaxe");
        registerVanillaTool(Items.IRON_AXE, "iron", "axe");
        registerVanillaTool(Items.IRON_SHOVEL, "iron", "shovel");
        registerVanillaTool(Items.IRON_HOE, "iron", "hoe");

        // Golden tools
        registerVanillaTool(Items.GOLDEN_SWORD, "golden", "sword");
        registerVanillaTool(Items.GOLDEN_PICKAXE, "golden", "pickaxe");
        registerVanillaTool(Items.GOLDEN_AXE, "golden", "axe");
        registerVanillaTool(Items.GOLDEN_SHOVEL, "golden", "shovel");
        registerVanillaTool(Items.GOLDEN_HOE, "golden", "hoe");

        // Diamond tools
        registerVanillaTool(Items.DIAMOND_SWORD, "diamond", "sword");
        registerVanillaTool(Items.DIAMOND_PICKAXE, "diamond", "pickaxe");
        registerVanillaTool(Items.DIAMOND_AXE, "diamond", "axe");
        registerVanillaTool(Items.DIAMOND_SHOVEL, "diamond", "shovel");
        registerVanillaTool(Items.DIAMOND_HOE, "diamond", "hoe");

        // Build set of recipe IDs to remove (vanilla tool crafting recipes)
        String[] tiers = {"wooden", "stone", "iron", "golden", "diamond"};
        String[] types = {"sword", "pickaxe", "axe", "shovel", "hoe"};
        for (String tier : tiers) {
            for (String type : types) {
                VANILLA_TOOL_RECIPES_TO_REMOVE.add(new ResourceLocation("minecraft", tier + "_" + type));
            }
        }
    }

    private static void registerVanillaTool(Item item, String tier, String type) {
        VANILLA_TOOL_TO_TIER.put(item, tier);
        VANILLA_TOOL_TO_TYPE.put(item, type);
    }

    @Override
    public CompletableFuture<Void> reload(PreparationBarrier stage, ResourceManager resourceManager,
                                          ProfilerFiller preparationsProfiler, ProfilerFiller reloadProfiler,
                                          Executor backgroundExecutor, Executor gameExecutor) {
        // Wait for recipe manager to finish loading, then modify recipes on the main thread
        return stage.wait(null).thenRunAsync(() -> {
            LOGGER.info("No More Vanilla Tools: Starting recipe modification...");

            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                LOGGER.warn("Server not available, skipping recipe modification");
                return;
            }

            RecipeManager recipeManager = server.getRecipeManager();
            Collection<Recipe<?>> allRecipes = recipeManager.getRecipes();

            List<Recipe<?>> modifiedRecipes = new ArrayList<>();
            int replacedCount = 0;
            int removedCount = 0;

            for (Recipe<?> recipe : allRecipes) {
                // Check if this is a vanilla tool crafting recipe and should be removed
                if (Config.removeVanillaToolCrafting && VANILLA_TOOL_RECIPES_TO_REMOVE.contains(recipe.getId())) {
                    if (Config.debugLogging) {
                        LOGGER.debug("Removing vanilla tool recipe: {}", recipe.getId());
                    }
                    removedCount++;
                    // Don't add to modified recipes (effectively removes it)
                    continue;
                }

                // Try to replace vanilla tools in the recipe
                Recipe<?> modifiedRecipe = replaceVanillaToolsInRecipe(recipe);
                if (modifiedRecipe != recipe) {
                    // Recipe was modified
                    replacedCount++;
                    if (Config.debugLogging) {
                        LOGGER.debug("Modified recipe: {}", recipe.getId());
                    }
                }
                modifiedRecipes.add(modifiedRecipe);
            }

            // Replace all recipes with modified versions
            recipeManager.replaceRecipes(modifiedRecipes);

            LOGGER.info("No More Vanilla Tools: Recipe modification complete! Removed {} recipes, modified {} recipes",
                    removedCount, replacedCount);

        }, gameExecutor);
    }

    @Override
    public String getName() {
        return "No More Vanilla Tools Recipe Modifier";
    }

    /**
     * Attempts to replace vanilla tools in a recipe with TinkerMaterialIngredient.
     *
     * @param recipe The recipe to modify
     * @return Modified recipe if changes were made, or original recipe if no changes
     */
    private Recipe<?> replaceVanillaToolsInRecipe(Recipe<?> recipe) {
        if (recipe instanceof ShapedRecipe shapedRecipe) {
            return replaceInShapedRecipe(shapedRecipe);
        } else if (recipe instanceof ShapelessRecipe shapelessRecipe) {
            return replaceInShapelessRecipe(shapelessRecipe);
        }
        // For other recipe types (cooking, smithing, etc.), we don't modify them
        // as they typically don't use tools as ingredients
        return recipe;
    }

    /**
     * Replaces vanilla tools in a shaped recipe.
     */
    private Recipe<?> replaceInShapedRecipe(ShapedRecipe recipe) {
        NonNullList<Ingredient> originalIngredients = recipe.getIngredients();
        boolean modified = false;
        NonNullList<Ingredient> newIngredients = NonNullList.create();

        for (Ingredient ingredient : originalIngredients) {
            Ingredient replacement = replaceIngredient(ingredient);
            if (replacement != ingredient) {
                modified = true;
            }
            newIngredients.add(replacement);
        }

        if (!modified) {
            return recipe;
        }

        // Create new shaped recipe with replaced ingredients using reflection/builder
        // For now, we use the original recipe but note that ingredients have been checked
        // TODO: Implement proper recipe rebuilding with ShapedRecipe.Builder or custom recipe type
        // This is a limitation that needs to be addressed in a future iteration

        if (Config.debugLogging) {
            LOGGER.warn("ShapedRecipe modification not yet fully implemented for: {}", recipe.getId());
        }

        return recipe; // Return original for now - will need custom recipe type
    }

    /**
     * Replaces vanilla tools in a shapeless recipe.
     */
    private Recipe<?> replaceInShapelessRecipe(ShapelessRecipe recipe) {
        NonNullList<Ingredient> originalIngredients = recipe.getIngredients();
        boolean modified = false;
        NonNullList<Ingredient> newIngredients = NonNullList.create();

        for (Ingredient ingredient : originalIngredients) {
            Ingredient replacement = replaceIngredient(ingredient);
            if (replacement != ingredient) {
                modified = true;
            }
            newIngredients.add(replacement);
        }

        if (!modified) {
            return recipe;
        }

        // Create new shapeless recipe with replaced ingredients
        // Note: ShapelessRecipe constructor signature in 1.20.1
        if (Config.debugLogging) {
            LOGGER.warn("ShapelessRecipe modification not yet fully implemented for: {}", recipe.getId());
        }

        return recipe; // Return original for now - will need custom recipe type
    }

    /**
     * Replaces a single ingredient if it contains vanilla tools.
     *
     * @param ingredient The ingredient to check and potentially replace
     * @return Replacement ingredient or original if no replacement needed
     */
    private Ingredient replaceIngredient(Ingredient ingredient) {
        // Check if this ingredient contains any vanilla tools
        for (net.minecraft.world.item.ItemStack stack : ingredient.getItems()) {
            Item item = stack.getItem();
            if (VANILLA_TOOL_TO_TIER.containsKey(item)) {
                // This ingredient contains a vanilla tool - replace it
                String tier = VANILLA_TOOL_TO_TIER.get(item);
                String toolType = VANILLA_TOOL_TO_TYPE.get(item);

                if (Config.debugLogging) {
                    LOGGER.debug("Replacing vanilla tool ingredient: {} -> TC {} {}", item, tier, toolType);
                }

                // Return our custom TinkerMaterialIngredient
                return new TinkerMaterialIngredient(tier, toolType);
            }
        }

        // No vanilla tools found, return original ingredient
        return ingredient;
    }
}
