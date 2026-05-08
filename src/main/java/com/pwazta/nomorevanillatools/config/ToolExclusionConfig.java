package com.pwazta.nomorevanillatools.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.pwazta.nomorevanillatools.loot.TinkerToolBuilder;
import com.pwazta.nomorevanillatools.loot.VanillaItemMappings;
import com.pwazta.nomorevanillatools.recipe.TinkerMaterialIngredient;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Configures which TC items are excluded from matching specific actions/slots/types.
 * Prevents cheap multi-tools (e.g., dagger) from substituting for expensive tools
 * (e.g., cleaver) in recipe slots, and ancient tools (e.g., war_pick) from appearing
 * as loot replacements.
 *
 * Config file: config/nomorevanillatools/tool_exclusions.json
 * Format: { "action_name": ["modid:tool_id", ...], ... }
 *
 * On first boot, creates defaults (ancient tools excluded from tool/ranged actions,
 * dagger from tool actions, slimesuit from armor slots).
 * On subsequent boots, loads from file (user edits preserved).
 */
public class ToolExclusionConfig {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<String, Set<String>> EXCLUSIONS = new HashMap<>();

    private static File configFile;
    private static boolean initialized = false;

    /**
     * Sets up config path and loads existing config, or creates defaults.
     * Called from commonSetup — no registry dependency.
     */
    public static void initialize(File configDir) {
        if (initialized) return;

        File modConfigDir = new File(configDir, "nomorevanillatools");
        if (!modConfigDir.exists()) modConfigDir.mkdirs();

        configFile = new File(modConfigDir, "tool_exclusions.json");

        if (configFile.exists()) {
            LOGGER.info("Loading tool exclusions from: {}", configFile.getAbsolutePath());
            loadConfig();
        } else {
            LOGGER.info("No tool exclusions config found — creating defaults");
            createDefaults();
            saveConfig();
        }

        initialized = true;
    }

    /** Tools excluded from all tool actions by default. Dagger is too cheap; ancient tools are uncraftable loot-only. */
    private static final List<String> DEFAULT_EXCLUDED_TOOLS = List.of(
        "tconstruct:dagger",
        "tconstruct:swasher",
        "tconstruct:war_pick",
        "tconstruct:battlesign",
        "tconstruct:melting_pan",
        "tconstruct:minotaur_axe"
    );

    /** Ranged excluded from all ranged types. war_pick: uncraftable ancient. blowpipe: defensive — JSON tool with 0 material parts; mistagging into ranged/crossbows would silently no-op replacement. */
    private static final List<String> DEFAULT_EXCLUDED_RANGED = List.of(
        "tconstruct:war_pick",
        "tinkers_things:blowpipe"
    );

    /** Shields excluded from shield slot. Cutlass has shield_core stats but is sword-shaped — visually confusing as a vanilla-shield replacement. */
    private static final List<String> DEFAULT_EXCLUDED_SHIELDS = List.of(
        "tinkers_thinking:cutlass"
    );

    /** Armor excluded from all armor slots by default. Slimesuit uses fixed stats, not material-based plating. */
    private static final List<String> DEFAULT_EXCLUDED_ARMOR = List.of(
        "tconstruct:slime_helmet",
        "tconstruct:slime_chestplate",
        "tconstruct:slime_leggings",
        "tconstruct:slime_boots"
    );

    /**
     * Creates the default exclusion set: tools excluded from tool actions, slimesuit from armor slots,
     * war_pick from ranged types (it's a ModifiableCrossbowItem but uncraftable ancient loot-only).
     */
    private static void createDefaults() {
        EXCLUSIONS.clear();
        for (String action : VanillaItemMappings.TOOL_TYPES) {
            EXCLUSIONS.put(action, new LinkedHashSet<>(DEFAULT_EXCLUDED_TOOLS));
        }
        for (String slot : VanillaItemMappings.ARMOR_SLOTS) {
            EXCLUSIONS.put(slot, new LinkedHashSet<>(DEFAULT_EXCLUDED_ARMOR));
        }
        for (String ranged : VanillaItemMappings.RANGED_TYPES) {
            EXCLUSIONS.put(ranged, new LinkedHashSet<>(DEFAULT_EXCLUDED_RANGED));
        }
        for (String shield : VanillaItemMappings.SHIELD_TYPES) {
            EXCLUSIONS.put(shield, new LinkedHashSet<>(DEFAULT_EXCLUDED_SHIELDS));
        }
        for (String rod : VanillaItemMappings.FISHING_ROD_TYPES) {
            EXCLUSIONS.put(rod, new LinkedHashSet<>());
        }
        for (String fs : VanillaItemMappings.FLINT_AND_STEEL_TYPES) {
            EXCLUSIONS.put(fs, new LinkedHashSet<>());
        }
    }

    /** Checks if an item is excluded from the given action/slot/type key. */
    public static boolean isExcluded(String actionName, String toolRegistryId) {
        Set<String> excluded = EXCLUSIONS.get(actionName.toLowerCase());
        return excluded != null && excluded.contains(toolRegistryId);
    }

    private static void saveConfig() {
        Map<String, List<String>> toSave = new LinkedHashMap<>();

        // Write known actions in consistent order
        for (String action : VanillaItemMappings.TOOL_TYPES) {
            Set<String> excluded = EXCLUSIONS.get(action);
            toSave.put(action, excluded != null ? new ArrayList<>(excluded) : new ArrayList<>());
        }
        for (String slot : VanillaItemMappings.ARMOR_SLOTS) {
            Set<String> excluded = EXCLUSIONS.get(slot);
            toSave.put(slot, excluded != null ? new ArrayList<>(excluded) : new ArrayList<>());
        }
        for (String ranged : VanillaItemMappings.RANGED_TYPES) {
            Set<String> excluded = EXCLUSIONS.get(ranged);
            toSave.put(ranged, excluded != null ? new ArrayList<>(excluded) : new ArrayList<>());
        }
        for (String shield : VanillaItemMappings.SHIELD_TYPES) {
            Set<String> excluded = EXCLUSIONS.get(shield);
            toSave.put(shield, excluded != null ? new ArrayList<>(excluded) : new ArrayList<>());
        }
        for (String rod : VanillaItemMappings.FISHING_ROD_TYPES) {
            Set<String> excluded = EXCLUSIONS.get(rod);
            toSave.put(rod, excluded != null ? new ArrayList<>(excluded) : new ArrayList<>());
        }
        for (String fs : VanillaItemMappings.FLINT_AND_STEEL_TYPES) {
            Set<String> excluded = EXCLUSIONS.get(fs);
            toSave.put(fs, excluded != null ? new ArrayList<>(excluded) : new ArrayList<>());
        }

        // Append any user-defined actions not in known types
        for (Map.Entry<String, Set<String>> entry : EXCLUSIONS.entrySet()) {
            if (!toSave.containsKey(entry.getKey())) {
                toSave.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }

        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(toSave, writer);
            LOGGER.debug("Saved tool exclusions config to: {}", configFile.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Failed to save tool exclusions config", e);
        }
    }

    private static void loadConfig() {
        EXCLUSIONS.clear();

        try (FileReader reader = new FileReader(configFile)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();

            for (String action : json.keySet()) {
                Set<String> excluded = new LinkedHashSet<>();
                json.getAsJsonArray(action).forEach(element -> excluded.add(element.getAsString()));
                EXCLUSIONS.put(action, excluded);
                LOGGER.debug("Loaded {} exclusions for action '{}'", excluded.size(), action);
            }

            LOGGER.info("Successfully loaded tool exclusions for {} actions", EXCLUSIONS.size());
        } catch (IOException e) {
            LOGGER.error("Failed to load tool exclusions config", e);
        } catch (Exception e) {
            LOGGER.error("Error parsing tool exclusions config", e);
        }
    }

    /**
     * Reloads exclusions from the config file.
     */
    public static void reload() {
        LOGGER.info("Reloading tool exclusions...");
        loadConfig();
        TinkerMaterialIngredient.clearDisplayCache();
        TinkerToolBuilder.clearCaches();
    }

    /**
     * Resets exclusions to defaults and saves. Used by the reset command after config files are deleted.
     */
    public static void resetToDefaults() {
        LOGGER.info("Resetting tool exclusions to defaults...");
        createDefaults();
        saveConfig();
        TinkerMaterialIngredient.clearDisplayCache();
        TinkerToolBuilder.clearCaches();
    }

    public static File getConfigFile() { return configFile; }
}
