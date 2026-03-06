package com.pwazta.nomorevanillatools.config;

import com.google.gson.*;
import com.mojang.logging.LogUtils;
import com.pwazta.nomorevanillatools.loot.EnchantmentConverter;
import org.slf4j.Logger;
import slimeknights.tconstruct.library.modifiers.ModifierId;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Configurable modifier category weights for enchantment-to-modifier conversion.
 * Controls which modifiers are "primary" (high chance), "secondary", or "misc" (low chance)
 * per tool category, and the weight values for each tier.
 *
 * Config file: config/nomorevanillatools/modifier_weights.json
 *
 * Follows the same lifecycle as {@link ModifierSkipListConfig}:
 * initialize (commonSetup) → reload (generate command) → resetToDefaults (generate reset).
 */
public class ModifierWeightsConfig {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ── Loaded state ─────────────────────────────────────────────────────

    /** tier name → weight value (e.g. "primary" → 0.60) */
    private static volatile Map<String, Double> tierWeights = Map.of();

    /** category → (modifierId → tier name) (e.g. "melee" → {"tconstruct:sharpness" → "primary"}) */
    private static volatile Map<String, Map<ModifierId, String>> categoryWeights = Map.of();

    private static File configFile;
    private static boolean initialized = false;

    // ── Default values (relative weights — ratio matters, not sum; 6:3:1 behaves the same as 0.6:0.3:0.1) ──

    private static final double DEFAULT_PRIMARY = 0.60;
    private static final double DEFAULT_SECONDARY = 0.30;
    private static final double DEFAULT_MISC = 0.10;

    // ── Lifecycle ────────────────────────────────────────────────────────

    public static void initialize(File configDir) {
        if (initialized) return;

        File modConfigDir = new File(configDir, "nomorevanillatools");
        if (!modConfigDir.exists()) modConfigDir.mkdirs();

        configFile = new File(modConfigDir, "modifier_weights.json");

        if (configFile.exists()) {
            LOGGER.info("Loading modifier weights from: {}", configFile.getAbsolutePath());
            loadConfig();
        } else {
            LOGGER.info("No modifier weights config found — creating defaults");
            writeDefaults();
            loadConfig();
        }

        initialized = true;
    }

    public static void reload() {
        LOGGER.info("Reloading modifier weights...");
        loadConfig();
        EnchantmentConverter.clearCache();
    }

    public static void resetToDefaults() {
        LOGGER.info("Resetting modifier weights to defaults...");
        writeDefaults();
        loadConfig();
        EnchantmentConverter.clearCache();
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Returns the weight for a modifier in the given tool category.
     * Unknown modifiers default to "misc" tier.
     */
    public static double getWeight(ModifierId modId, String category) {
        Map<ModifierId, String> catMap = categoryWeights.get(category);
        String tier = catMap != null ? catMap.getOrDefault(modId, "misc") : "misc";
        return tierWeights.getOrDefault(tier, DEFAULT_MISC);
    }

    public static File getConfigFile() { return configFile; }

    // ── Load/Save ────────────────────────────────────────────────────────

    private static void loadConfig() {
        try (FileReader reader = new FileReader(configFile)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

            // Parse tier weights
            Map<String, Double> weights = new HashMap<>();
            if (root.has("tier_weights")) {
                JsonObject tw = root.getAsJsonObject("tier_weights");
                for (Map.Entry<String, JsonElement> entry : tw.entrySet()) {
                    weights.put(entry.getKey(), entry.getValue().getAsDouble());
                }
            }
            // Ensure defaults exist
            weights.putIfAbsent("primary", DEFAULT_PRIMARY);
            weights.putIfAbsent("secondary", DEFAULT_SECONDARY);
            weights.putIfAbsent("misc", DEFAULT_MISC);
            tierWeights = Map.copyOf(weights);

            // Parse category mappings
            Map<String, Map<ModifierId, String>> categories = new HashMap<>();
            if (root.has("categories")) {
                JsonObject cats = root.getAsJsonObject("categories");
                for (Map.Entry<String, JsonElement> catEntry : cats.entrySet()) {
                    Map<ModifierId, String> modMap = new HashMap<>();
                    JsonObject modifiers = catEntry.getValue().getAsJsonObject();
                    for (Map.Entry<String, JsonElement> modEntry : modifiers.entrySet()) {
                        ModifierId id = ModifierId.tryParse(modEntry.getKey());
                        if (id != null) {
                            modMap.put(id, modEntry.getValue().getAsString());
                        } else {
                            LOGGER.warn("Invalid modifier ID in weights config: {}", modEntry.getKey());
                        }
                    }
                    categories.put(catEntry.getKey(), Map.copyOf(modMap));
                }
            }
            categoryWeights = Map.copyOf(categories);

            LOGGER.info("Loaded modifier weights: {} tiers, {} categories",
                tierWeights.size(), categoryWeights.size());
        } catch (IOException e) {
            LOGGER.error("Failed to load modifier weights config", e);
        } catch (Exception e) {
            LOGGER.error("Error parsing modifier weights config", e);
        }
    }

    private static void writeDefaults() {
        JsonObject root = new JsonObject();
        root.addProperty("_comment", "Weights are ratios (should add to 1.0 for consistency, but any ratio works — 6:3:1 behaves the same as 0.6:0.3:0.1). Custom tiers supported: add e.g. \"epic\": 0.80 to tier_weights, then assign modifiers to it in categories.");

        // Tier weights
        JsonObject tw = new JsonObject();
        tw.addProperty("primary", DEFAULT_PRIMARY);
        tw.addProperty("secondary", DEFAULT_SECONDARY);
        tw.addProperty("misc", DEFAULT_MISC);
        root.add("tier_weights", tw);

        // Category mappings (same as the old hardcoded buildCategoryWeights)
        JsonObject categories = new JsonObject();

        JsonObject melee = new JsonObject();
        for (String m : new String[]{"sharpness", "smite", "bane_of_sssss", "fiery", "severing", "necrotic"})
            melee.addProperty("tconstruct:" + m, "primary");
        for (String m : new String[]{"knockback", "luck", "sweeping_edge", "experienced"})
            melee.addProperty("tconstruct:" + m, "secondary");
        for (String m : new String[]{"reinforced", "soulbound"})
            melee.addProperty("tconstruct:" + m, "misc");
        categories.add("melee", melee);

        JsonObject mining = new JsonObject();
        for (String m : new String[]{"haste", "fortune", "experienced"})
            mining.addProperty("tconstruct:" + m, "primary");
        for (String m : new String[]{"sharpness", "reinforced"})
            mining.addProperty("tconstruct:" + m, "secondary");
        for (String m : new String[]{"knockback", "fiery"})
            mining.addProperty("tconstruct:" + m, "misc");
        categories.add("mining", mining);

        JsonObject armor = new JsonObject();
        for (String m : new String[]{"protection", "fire_protection", "blast_protection", "projectile_protection", "thorns"})
            armor.addProperty("tconstruct:" + m, "primary");
        for (String m : new String[]{"reinforced", "respiration"})
            armor.addProperty("tconstruct:" + m, "secondary");
        for (String m : new String[]{"aqua_affinity", "soulbound"})
            armor.addProperty("tconstruct:" + m, "misc");
        categories.add("armor", armor);

        JsonObject ranged = new JsonObject();
        for (String m : new String[]{"power", "punch", "quick_charge"})
            ranged.addProperty("tconstruct:" + m, "primary");
        for (String m : new String[]{"fiery", "piercing"})
            ranged.addProperty("tconstruct:" + m, "secondary");
        for (String m : new String[]{"reinforced", "experienced"})
            ranged.addProperty("tconstruct:" + m, "misc");
        categories.add("ranged", ranged);

        root.add("categories", categories);

        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(root, writer);
            LOGGER.debug("Wrote default modifier weights to: {}", configFile.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Failed to write modifier weights defaults", e);
        }
    }
}
