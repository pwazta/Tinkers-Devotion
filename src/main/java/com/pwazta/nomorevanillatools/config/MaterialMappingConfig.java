package com.pwazta.nomorevanillatools.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Configuration system for mapping vanilla material tiers to Tinker's Construct materials.
 * Loads from JSON file and provides lookup functionality.
 */
public class MaterialMappingConfig {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Cached material mappings: tier -> set of material IDs
    private static final Map<String, Set<String>> MATERIAL_MAPPINGS = new HashMap<>();

    private static File configFile;

    /**
     * Initializes the material mapping configuration.
     * Creates default config if it doesn't exist, otherwise loads from file.
     *
     * @param configDir The config directory (usually FMLPaths.CONFIGDIR.get().toFile())
     */
    public static void initialize(File configDir) {
        // Create nomorevanillatools subdirectory if it doesn't exist
        File modConfigDir = new File(configDir, "nomorevanillatools");
        if (!modConfigDir.exists()) {
            modConfigDir.mkdirs();
        }

        configFile = new File(modConfigDir, "material_mappings.json");

        if (!configFile.exists()) {
            LOGGER.info("Material mappings config not found, creating default config at: {}", configFile.getAbsolutePath());
            createDefaultConfig();
        } else {
            LOGGER.info("Loading material mappings from: {}", configFile.getAbsolutePath());
            loadConfig();
        }
    }

    /**
     * Creates the default material mappings configuration file.
     */
    private static void createDefaultConfig() {
        Map<String, List<String>> defaultMappings = new LinkedHashMap<>();

        // Wooden tier - includes wood and bamboo
        defaultMappings.put("wooden", Arrays.asList(
                "tconstruct:wood",
                "tconstruct:bamboo"
        ));

        // Stone tier - includes rock, flint, and basalt
        defaultMappings.put("stone", Arrays.asList(
                "tconstruct:rock",
                "tconstruct:flint",
                "tconstruct:basalt"
        ));

        // Iron tier - includes iron and pig iron
        defaultMappings.put("iron", Arrays.asList(
                "tconstruct:iron",
                "tconstruct:pig_iron"
        ));

        // Golden tier - includes gold and rose gold
        defaultMappings.put("golden", Arrays.asList(
                "tconstruct:gold",
                "tconstruct:rose_gold"
        ));

        // Diamond tier - just diamond
        defaultMappings.put("diamond", Arrays.asList(
                "tconstruct:diamond"
        ));

        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(defaultMappings, writer);
            LOGGER.info("Created default material mappings config");

            // Load the default mappings into memory
            for (Map.Entry<String, List<String>> entry : defaultMappings.entrySet()) {
                MATERIAL_MAPPINGS.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
        } catch (IOException e) {
            LOGGER.error("Failed to create default material mappings config", e);
        }
    }

    /**
     * Loads the material mappings from the config file.
     */
    private static void loadConfig() {
        MATERIAL_MAPPINGS.clear();

        try (FileReader reader = new FileReader(configFile)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();

            // Parse each tier entry
            for (String tier : json.keySet()) {
                Set<String> materials = new HashSet<>();
                json.getAsJsonArray(tier).forEach(element -> materials.add(element.getAsString()));
                MATERIAL_MAPPINGS.put(tier, materials);
                LOGGER.debug("Loaded {} materials for tier '{}'", materials.size(), tier);
            }

            LOGGER.info("Successfully loaded material mappings for {} tiers", MATERIAL_MAPPINGS.size());
        } catch (IOException e) {
            LOGGER.error("Failed to load material mappings config, using defaults", e);
            createDefaultConfig();
        } catch (Exception e) {
            LOGGER.error("Error parsing material mappings config, using defaults", e);
            createDefaultConfig();
        }
    }

    /**
     * Reloads the material mappings from the config file.
     * Useful for /reload command support.
     */
    public static void reload() {
        LOGGER.info("Reloading material mappings...");
        loadConfig();
    }

    /**
     * Gets the set of TC material IDs that count as a specific vanilla tier.
     *
     * @param tier The vanilla tier (wooden, stone, iron, golden, diamond)
     * @return Set of TC material IDs, or null if tier not found
     */
    public static Set<String> getMaterialsForTier(String tier) {
        return MATERIAL_MAPPINGS.get(tier.toLowerCase());
    }

    /**
     * Checks if a specific TC material ID matches a vanilla tier.
     *
     * @param tier The vanilla tier
     * @param materialId The TC material ID (e.g., "tconstruct:iron")
     * @return true if the material is valid for the tier
     */
    public static boolean isMaterialValidForTier(String tier, String materialId) {
        Set<String> materials = getMaterialsForTier(tier);
        return materials != null && materials.contains(materialId);
    }

    /**
     * Gets all configured tiers.
     *
     * @return Set of tier names
     */
    public static Set<String> getAllTiers() {
        return MATERIAL_MAPPINGS.keySet();
    }

    /**
     * Gets the config file location.
     *
     * @return The config file
     */
    public static File getConfigFile() {
        return configFile;
    }
}
