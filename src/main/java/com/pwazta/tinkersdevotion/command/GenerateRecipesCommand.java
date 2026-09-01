package com.pwazta.tinkersdevotion.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import com.pwazta.tinkersdevotion.Config;
import com.pwazta.tinkersdevotion.TinkersDevotion;
import com.pwazta.tinkersdevotion.config.ModifierSkipListConfig;
import com.pwazta.tinkersdevotion.config.TiersToTcMaterials;
import com.pwazta.tinkersdevotion.config.ToolExclusionConfig;
import com.pwazta.tinkersdevotion.datagen.DatapackHelper;
import com.pwazta.tinkersdevotion.loot.VanillaItemMappings;
import com.pwazta.tinkersdevotion.recipe.ArmorMode;
import com.pwazta.tinkersdevotion.recipe.FishingRodMode;
import com.pwazta.tinkersdevotion.recipe.FlintAndSteelMode;
import com.pwazta.tinkersdevotion.recipe.IngredientMode;
import com.pwazta.tinkersdevotion.recipe.RangedMode;
import com.pwazta.tinkersdevotion.recipe.ShieldMode;
import com.pwazta.tinkersdevotion.recipe.ToolMode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command to generate datapack recipes replacing vanilla tool/armor/ranged ingredients with TC equivalents.
 * Reloads exclusion configs, scans loaded crafting recipes, writes replacements, cleans stale files, and triggers a datapack reload.
 */
public class GenerateRecipesCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * One preset per recipe type, because their scopes genuinely differ: every replaced item on the
     * crafting side, but only the netherite upgrades on the smithing side. A shared preset would
     * also match {@code SmithingTrimRecipe}, whose {@code getResultItem} returns a placeholder iron
     * chestplate — taking all 16 armor trim recipes with it.
     */
    private static final List<String> REMOVAL_PRESETS =
        List.of(TinkersDevotion.MODID + ":vanilla_crafting", TinkersDevotion.MODID + ":vanilla_smithing");

    /**
     * Mirrors {@link VanillaItemMappings#replacedItemIds()} plus shears, which has no TC item
     * equivalent but is covered by TC's shearing modifiers. Datapack-overridable, unlike a
     * hardcoded list.
     */
    private static final TagKey<Item> DISABLED_VANILLA_CRAFTING =
        ItemTags.create(new ResourceLocation(TinkersDevotion.MODID, "disabled_vanilla_crafting"));

    // ── Inner types ───────────────────────────────────────────────────────────

    /** Tracks everything that happened during generation for player feedback. */
    private static class GenerationResult {
        int recipesGenerated = 0;
        int vanillaRecipesRemoved = 0;
        int mantleStubsCleaned = 0;
        int staleRecipesCleaned = 0;
        List<String> unmappedToolMaterials = List.of();
        final List<String> errors = new ArrayList<>();
    }

    /** Pre-built replacement JSON for a slot containing at least one tracked vanilla item entry. */
    private record ReplacementEntry(int index, JsonElement replacementJson) {}

    // ── Command entry point ───────────────────────────────────────────────────

    /**
     * Executes the generate command: captures addon-pack diagnostics, reloads the disk-backed
     * configs, then runs {@link #regenerate} and reports what happened.
     */
    public static int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        MinecraftServer server = context.getSource().getServer();
        CommandSourceStack source = context.getSource();

        try {
            GenerationResult result = new GenerationResult();

            // Step 1: Capture tool materials dropped from the last cache rebuild because their
            // vanilla Tier is unmappable. Diagnostic feedback for addon-pack authors — not user
            // exclusions. The cache itself is rebuilt on MaterialsLoadedEvent, not here.
            result.unmappedToolMaterials = TiersToTcMaterials.getUnmappedToolMaterials();

            // Step 2: Reload tool/armor exclusions and modifier skip list from disk
            ToolExclusionConfig.reload();
            ModifierSkipListConfig.reload();

            // Step 3: Regenerate everything (scan, write, disable, reload)
            source.sendSuccess(() -> Component.literal("Reloading datapacks..."), false);
            regenerate(server, result);

            // Step 4: Send detailed feedback
            sendFeedback(source, result);
            source.sendSuccess(() -> Component.literal("Datapack reload complete! Recipes are now active."), true);

            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            LOGGER.error("Failed to generate recipes", e);
            source.sendFailure(Component.literal("Failed to generate recipes: " + e.getMessage()));
            return 0;
        }
    }

    // ── Reset subcommand ──────────────────────────────────────────────────────

    /**
     * Resets all disk-backed configs to defaults and regenerates everything from scratch.
     * Tool tier state lives in the TC registry (no file to delete).
     */
    public static int runReset(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        source.sendSuccess(() -> Component.literal("Resetting all configs to defaults..."), true);

        // Reset tool exclusions and modifier skip list to defaults.
        // Tool tier state lives in the TC registry — no config file to delete.
        ToolExclusionConfig.resetToDefaults();
        ModifierSkipListConfig.resetToDefaults();

        source.sendSuccess(() -> Component.literal("Configs reset. Regenerating..."), false);
        return run(context);
    }

    // ── Auto-boot entry point ─────────────────────────────────────────────────

    /** @return replacement recipes generated */
    public static int generate(MinecraftServer server) throws Exception {
        GenerationResult result = new GenerationResult();
        regenerate(server, result);
        if (result.mantleStubsCleaned > 0) LOGGER.info("Cleared {} Mantle recipe stub(s)", result.mantleStubsCleaned);
        if (result.staleRecipesCleaned > 0) LOGGER.info("Cleaned {} stale recipe(s) from previous generation", result.staleRecipesCleaned);
        return result.recipesGenerated;
    }

    private static int cleanStaleRecipes(Set<Path> existing, Set<Path> written) {
        int cleaned = 0;
        for (Path path : existing) {
            if (!written.contains(path) && DatapackHelper.deleteRecipeFile(path)) cleaned++;
        }
        return cleaned;
    }

    // ── Core generation logic ─────────────────────────────────────────────────

    /**
     * Scans all loaded recipes, finds vanilla tool ingredients, and writes replacement recipes.
     *
     * @return Set of file paths written (for stale recipe detection)
     */
    private static Set<Path> doGenerate(MinecraftServer server, GenerationResult result) throws Exception {
        Set<Path> writtenFiles = new HashSet<>();
        RecipeManager recipeManager = server.getRecipeManager();
        Path datapackPath = DatapackHelper.getDatapackPath(server);
        Path dataPath = datapackPath.resolve("data");

        DatapackHelper.saveMcmeta(datapackPath);

        // Scan all recipes and generate replacements
        for (Recipe<?> recipe : recipeManager.getRecipes()) {
            List<Ingredient> ingredients = extractScannableIngredients(recipe);
            if (ingredients == null) continue;

            List<ReplacementEntry> replacements = findVanillaItems(ingredients);
            if (replacements.isEmpty()) continue;

            // Vanilla output: Mantle disables the original, don't add a replacement path.
            ItemStack out = recipe.getResultItem(server.registryAccess());
            ResourceLocation outId = ForgeRegistries.ITEMS.getKey(out.getItem());
            if (outId != null && VanillaItemMappings.getReplacementInfoById(outId.toString()) != null) continue;

            try {
                JsonObject replacementRecipe = buildReplacementRecipe(recipe, replacements, server);
                if (replacementRecipe != null) {
                    ResourceLocation recipeId = recipe.getId();
                    String recipeName = recipeId.getPath() + "_tinker_replacement";
                    Path written = DatapackHelper.saveRecipeJson(
                        replacementRecipe, dataPath, recipeId.getNamespace(), recipeName);
                    writtenFiles.add(written);
                    result.recipesGenerated++;
                }
            } catch (Exception e) {
                ResourceLocation id = recipe.getId();
                LOGGER.warn("Skipping recipe '{}': {}", id, e.getMessage());
                result.errors.add("Skipped " + id + ": " + e.getMessage());
            }
        }

        LOGGER.info("Generated {} replacement recipes", result.recipesGenerated);
        return writtenFiles;
    }

    // ── Mantle delegation ─────────────────────────────────────────────────────

    /**
     * Scan, write, disable, reload — in the one order that converges in a single pass. Requires the
     * overworld ({@code ServerStartedEvent} or later), since Mantle reads {@code source.getLevel()}.
     *
     * <p>Clearing Mantle's stubs and reloading has to come first. A stub suppresses its recipe at
     * load, so neither our scan nor Mantle's own can see it — leaving a stale stub in place would
     * both skip that recipe's replacement and outlive every regeneration.
     * {@link MinecraftServer#reloadResources} blocks when called on the server thread, so this
     * reads as straight-line code.
     */
    private static void regenerate(MinecraftServer server, GenerationResult result) throws Exception {
        result.mantleStubsCleaned = DatapackHelper.cleanupMantleRecipeStubs(server);
        DatapackHelper.discoverAndReload(server).join();

        Set<Path> existingFiles = DatapackHelper.listGeneratedRecipeFiles(server);
        Set<Path> writtenFiles = doGenerate(server, result);
        result.staleRecipesCleaned = cleanStaleRecipes(existingFiles, writtenFiles);

        if (Config.disableVanillaCrafting) result.vanillaRecipesRemoved = disableVanillaRecipes(server);

        DatapackHelper.discoverAndReload(server).join();
        DatapackHelper.markGenerated(server);
    }

    /**
     * Dispatches our removal presets, which match recipe results against our own item tags. Scoped
     * to item IDs rather than the vanilla tool/armor tags TC's own preset uses — those tags carry
     * modded gear too, whose recipes are none of our business.
     */
    private static int disableVanillaRecipes(MinecraftServer server) {
        warnUncoveredReplacements();
        CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
        int count = 0;
        for (String preset : REMOVAL_PRESETS) {
            try {
                count += server.getCommands().getDispatcher()
                    .execute("mantle remove recipes preset " + preset, source);
            } catch (CommandSyntaxException e) {
                LOGGER.error("Failed to dispatch removal preset '{}' — Mantle dependency missing or broken?", preset, e);
            }
        }
        return count;
    }

    /** A replaced item left out of the tag stays craftable, silently defeating its replacement. */
    private static void warnUncoveredReplacements() {
        Set<String> covered = new HashSet<>();
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(DISABLED_VANILLA_CRAFTING)) {
            covered.add(BuiltInRegistries.ITEM.getKey(holder.value()).toString());
        }
        List<String> uncovered = VanillaItemMappings.replacedItemIds().stream().filter(id -> !covered.contains(id)).sorted().toList();
        if (!uncovered.isEmpty()) {
            LOGGER.error("Replaced items missing from #{} — their recipes stay craftable: {}", DISABLED_VANILLA_CRAFTING.location(), uncovered);
        }
    }

    // ── Recipe scanning ───────────────────────────────────────────────────────

    /**
     * Returns the list of ingredients to scan for a supported recipe type, or null for unsupported types.
     * {@code SmithingRecipe} does not override {@code getIngredients()} (default returns empty),
     * so smithing recipes need explicit per-slot extraction via AT-exposed fields.
     */
    private static @Nullable List<Ingredient> extractScannableIngredients(Recipe<?> recipe) {
        if (recipe instanceof CraftingRecipe) {
            return recipe.getIngredients();
        }
        if (recipe instanceof SmithingTransformRecipe t) {
            return List.of(t.template, t.base, t.addition);
        }
        // SmithingTrimRecipe intentionally skipped — base is always the `minecraft:trimmable_armor` tag,
        // which the tag-safety guard below would reject anyway. TC armor is not in that tag.
        return null;
    }

    /**
     * Per-element rewrite: tracked-vanilla {@code {"item":"..."}} entries become our {@link IngredientMode} JSON;
     * tags, custom-type ingredients, and non-tracked items are preserved. Forge re-parses mixed-child arrays
     * into a {@code CompoundIngredient} with OR-test semantics.
     */
    private static List<ReplacementEntry> findVanillaItems(List<Ingredient> ingredients) {
        List<ReplacementEntry> replacements = new ArrayList<>();
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            if (ingredient.isEmpty()) continue;
            JsonElement rewritten = rewriteIngredientJson(ingredient.toJson());
            if (rewritten != null) replacements.add(new ReplacementEntry(i, rewritten));
        }
        return replacements;
    }

    /** Returns rewritten JSON if any element was replaced, else {@code null} (slot unchanged). */
    private static @Nullable JsonElement rewriteIngredientJson(JsonElement originalJson) {
        if (originalJson.isJsonArray()) {
            JsonArray rewritten = new JsonArray();
            boolean changed = false;
            for (JsonElement element : originalJson.getAsJsonArray()) {
                JsonElement replacement = tryReplaceVanillaItemEntry(element);
                if (replacement != null) { rewritten.add(replacement); changed = true; }
                else rewritten.add(element);
            }
            return changed ? rewritten : null;
        }
        return tryReplaceVanillaItemEntry(originalJson);
    }

    /** Returns {@link IngredientMode#toJson()} if {@code element} is {@code {"item":"<tracked id>"}}, else {@code null}. */
    private static @Nullable JsonElement tryReplaceVanillaItemEntry(JsonElement element) {
        if (!element.isJsonObject()) return null;
        JsonObject obj = element.getAsJsonObject();
        if (obj.has("type") || obj.has("tag") || !obj.has("item")) return null;
        String itemId = obj.get("item").getAsString();
        VanillaItemMappings.ReplacementInfo info = VanillaItemMappings.getReplacementInfoById(itemId);
        if (info == null) return null;
        return toIngredientMode(info).toJson();
    }

    /** Bridges loot-side ReplacementInfo to recipe-side IngredientMode. */
    private static IngredientMode toIngredientMode(VanillaItemMappings.ReplacementInfo info) {
        if (info instanceof VanillaItemMappings.ToolInfo t) {
            return new ToolMode(t.tier(), t.toolType());
        } else if (info instanceof VanillaItemMappings.ArmorInfo a) {
            return new ArmorMode(a.slot(), null, a.minTier(), a.maxTier());
        } else if (info instanceof VanillaItemMappings.RangedInfo r) {
            return new RangedMode(r.rangedType(), r.canonicalMaterials());
        } else if (info instanceof VanillaItemMappings.ShieldInfo s) {
            return new ShieldMode(s.shieldType(), s.canonicalMaterials());
        } else if (info instanceof VanillaItemMappings.FishingRodInfo f) {
            return new FishingRodMode(f.fishingRodType(), f.canonicalMaterials());
        } else if (info instanceof VanillaItemMappings.FlintAndSteelInfo fs) {
            return new FlintAndSteelMode(fs.flintAndSteelType(), fs.tcItemIds());
        }
        throw new IllegalStateException("Unknown ReplacementInfo type: " + info.getClass());
    }

    // ── Recipe building ───────────────────────────────────────────────────────

    private static JsonObject buildReplacementRecipe(Recipe<?> recipe, List<ReplacementEntry> replacements, MinecraftServer server) {
        if (recipe instanceof ShapedRecipe shaped) {
            return buildShapedReplacement(shaped, replacements, server);
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            return buildShapelessReplacement(shapeless, replacements, server);
        } else if (recipe instanceof SmithingTransformRecipe transform) {
            return buildSmithingTransformReplacement(transform, replacements, server);
        }
        return null;
    }

    private static JsonObject createResultJson(Recipe<?> recipe, MinecraftServer server) {
        JsonObject result = new JsonObject();
        var resultItem = recipe.getResultItem(server.registryAccess());
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(resultItem.getItem());
        result.addProperty("item", itemId != null ? itemId.toString() : "minecraft:air");
        if (resultItem.getCount() > 1) result.addProperty("count", resultItem.getCount());
        return result;
    }

    private static JsonObject buildShapedReplacement(ShapedRecipe recipe, List<ReplacementEntry> replacements, MinecraftServer server) {
        JsonObject recipeJson = new JsonObject();
        recipeJson.addProperty("type", "minecraft:crafting_shaped");

        if (!recipe.getGroup().isEmpty()) recipeJson.addProperty("group", recipe.getGroup());
        recipeJson.addProperty("category", recipe.category().getSerializedName());

        int width = recipe.getWidth();
        int height = recipe.getHeight();
        NonNullList<Ingredient> ingredients = recipe.getIngredients();

        // Dedupe by serialized ingredient JSON: identical slots share one key char.
        JsonArray pattern = new JsonArray();
        JsonObject key = new JsonObject();
        Map<String, String> keyByJson = new HashMap<>();
        char nextKey = 'A';

        for (int row = 0; row < height; row++) {
            StringBuilder rowPattern = new StringBuilder();
            for (int col = 0; col < width; col++) {
                int index = row * width + col;
                if (index >= ingredients.size() || ingredients.get(index).isEmpty()) {
                    rowPattern.append(' ');
                    continue;
                }
                JsonElement ingJson = ingredientJsonFor(ingredients.get(index), index, replacements);
                String jsonStr = ingJson.toString();
                String keyChar = keyByJson.get(jsonStr);
                if (keyChar == null) {
                    keyChar = String.valueOf(nextKey++);
                    keyByJson.put(jsonStr, keyChar);
                    key.add(keyChar, ingJson);
                }
                rowPattern.append(keyChar);
            }
            pattern.add(rowPattern.toString());
        }

        recipeJson.add("pattern", pattern);
        recipeJson.add("key", key);
        recipeJson.add("result", createResultJson(recipe, server));
        return recipeJson;
    }

    private static JsonObject buildShapelessReplacement(ShapelessRecipe recipe, List<ReplacementEntry> replacements, MinecraftServer server) {
        JsonObject recipeJson = new JsonObject();
        recipeJson.addProperty("type", "minecraft:crafting_shapeless");

        if (!recipe.getGroup().isEmpty()) recipeJson.addProperty("group", recipe.getGroup());
        recipeJson.addProperty("category", recipe.category().getSerializedName());

        JsonArray ingredientsArray = new JsonArray();
        NonNullList<Ingredient> ingredients = recipe.getIngredients();

        for (int i = 0; i < ingredients.size(); i++) {
            ingredientsArray.add(ingredientJsonFor(ingredients.get(i), i, replacements));
        }
        recipeJson.add("ingredients", ingredientsArray);

        recipeJson.add("result", createResultJson(recipe, server));
        return recipeJson;
    }

    /**
     * Smithing transform has three named slots (template, base, addition) matching the scan order
     * emitted by {@link #extractScannableIngredients}. Per-slot replacement preserves template/addition
     * when not tracked (e.g. modded upgrade templates).
     */
    private static JsonObject buildSmithingTransformReplacement(SmithingTransformRecipe recipe, List<ReplacementEntry> replacements, MinecraftServer server) {
        JsonObject recipeJson = new JsonObject();
        recipeJson.addProperty("type", "minecraft:smithing_transform");

        recipeJson.add("template", ingredientJsonFor(recipe.template, 0, replacements));
        recipeJson.add("base",     ingredientJsonFor(recipe.base,     1, replacements));
        recipeJson.add("addition", ingredientJsonFor(recipe.addition, 2, replacements));

        recipeJson.add("result", createResultJson(recipe, server));
        return recipeJson;
    }

    /** Emits the precomputed replacement JSON if this index was tracked; otherwise preserves the original. */
    private static JsonElement ingredientJsonFor(Ingredient original, int index, List<ReplacementEntry> replacements) {
        ReplacementEntry replacement = findReplacementForIndex(replacements, index);
        return replacement != null ? replacement.replacementJson() : original.toJson();
    }

    /** Finds the replacement for a given ingredient index, or null. */
    private static ReplacementEntry findReplacementForIndex(List<ReplacementEntry> replacements, int index) {
        for (ReplacementEntry r : replacements) {
            if (r.index == index) return r;
        }
        return null;
    }

    // ── Player feedback ───────────────────────────────────────────────────────

    private static void sendFeedback(CommandSourceStack source, GenerationResult result) {
        source.sendSuccess(() -> Component.literal("=== Recipe Generation Complete ==="), true);

        if (!result.unmappedToolMaterials.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                "  Warning: " + result.unmappedToolMaterials.size() + " tool materials have unmapped modded tiers (dropped from pool)"), false);
            for (String unmapped : result.unmappedToolMaterials) {
                source.sendSuccess(() -> Component.literal("    - " + unmapped), false);
            }
        }

        // Recipes
        source.sendSuccess(() -> Component.literal(
            "  Recipes: " + result.recipesGenerated + " replacement recipes generated"), false);

        source.sendSuccess(() -> Component.literal(Config.disableVanillaCrafting
            ? "  Recipes: " + result.vanillaRecipesRemoved + " vanilla recipes disabled (via Mantle)"
            : "  Recipes: vanilla crafting re-enabled"), false);

        if (result.staleRecipesCleaned > 0) {
            source.sendSuccess(() -> Component.literal(
                "  Cleanup: " + result.staleRecipesCleaned + " stale recipes removed (from uninstalled mods)"), false);
        }

        for (String error : result.errors) {
            source.sendFailure(Component.literal("  Error: " + error));
        }
    }
}
