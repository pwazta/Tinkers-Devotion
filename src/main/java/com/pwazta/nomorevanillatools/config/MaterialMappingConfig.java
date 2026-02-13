package com.pwazta.nomorevanillatools.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.pwazta.nomorevanillatools.recipe.TinkerMaterialIngredient;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Tier;
import net.minecraftforge.common.TierSortingRegistry;
import org.slf4j.Logger;
import slimeknights.tconstruct.library.materials.IMaterialRegistry;
import slimeknights.tconstruct.library.materials.MaterialRegistry;
import slimeknights.tconstruct.library.materials.definition.IMaterial;
import slimeknights.tconstruct.library.materials.definition.MaterialId;
import slimeknights.tconstruct.tools.stats.HeadMaterialStats;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Maps vanilla material tiers to Tinker's Construct material IDs.
 * On first boot, auto-generates from TC's MaterialRegistry (HeadMaterialStats.tier()).
 * On subsequent boots, loads from config file (user edits preserved).
 *
 * Two-phase initialization:
 *   Phase 1 (commonSetup): load existing config file if present
 *   Phase 2 (MaterialsLoadedEvent): generate from registry if config is missing, or merge if force-regenerate
 */
public class MaterialMappingConfig {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Map<String, Set<String>> MATERIAL_MAPPINGS = new HashMap<>();

    /** Maps TierSortingRegistry ResourceLocations to config tier names. Modded tiers not listed here are skipped. */
    private static final Map<ResourceLocation, String> TIER_NAME_MAP = Map.of(
        new ResourceLocation("minecraft", "wood"),      "wooden",
        new ResourceLocation("minecraft", "stone"),     "stone",
        new ResourceLocation("minecraft", "iron"),      "iron",
        new ResourceLocation("minecraft", "gold"),      "golden",
        new ResourceLocation("minecraft", "diamond"),   "diamond",
        new ResourceLocation("minecraft", "netherite"), "netherite"
    );

    /** Consistent tier ordering for config file output. */
    private static final List<String> TIER_ORDER = List.of("wooden", "stone", "iron", "golden", "diamond", "netherite");

    private static File configFile;
    private static boolean initialized = false;

    /**
     * Phase 1: Sets up config path and loads existing config if present.
     * If no config file exists, MATERIAL_MAPPINGS stays empty until Phase 2.
     * Called from commonSetup (registry not available yet).
     */
    public static void initialize(File configDir) {
        if (initialized) return;

        File modConfigDir = new File(configDir, "nomorevanillatools");
        if (!modConfigDir.exists()) {
            modConfigDir.mkdirs();
        }

        configFile = new File(modConfigDir, "material_mappings.json");

        if (configFile.exists()) {
            LOGGER.info("Loading material mappings from: {}", configFile.getAbsolutePath());
            loadConfig();
        } else {
            LOGGER.info("No material mappings config found — will auto-generate from TC registry when materials load");
        }

        initialized = true;
    }

    /**
     * Phase 2: Generates config from TC MaterialRegistry if needed.
     * Called when TC materials are loaded (MaterialsLoadedEvent) — fires on both client and server.
     *
     * - If MATERIAL_MAPPINGS is empty (first boot or corrupt config): generates from registry
     * - If forceRegenerate: merges registry data with existing config (user additions preserved)
     */
    public static void generateIfNeeded(boolean forceRegenerate) {
        if (configFile == null) {
            LOGGER.error("MaterialMappingConfig not initialized, cannot generate");
            return;
        }

        if (!MaterialRegistry.isFullyLoaded()) {
            LOGGER.warn("TC MaterialRegistry not fully loaded, skipping material auto-generation");
            return;
        }

        if (MATERIAL_MAPPINGS.isEmpty()) {
            generateFromRegistry();
        } else if (forceRegenerate) {
            mergeWithRegistry();
        }
    }

    /**
     * Scans TC MaterialRegistry for all visible materials with HeadMaterialStats,
     * groups them by harvest tier, writes the config, and loads into memory.
     */
    private static void generateFromRegistry() {
        LOGGER.info("Auto-generating material mappings from TC registry...");

        Map<String, Set<String>> generated = scanRegistryForTiers();

        if (generated.isEmpty()) {
            LOGGER.warn("No materials with HeadMaterialStats found in TC registry — config not generated");
            return;
        }

        MATERIAL_MAPPINGS.clear();
        MATERIAL_MAPPINGS.putAll(generated);
        saveConfig();
        TinkerMaterialIngredient.clearDisplayCache();

        int totalMaterials = generated.values().stream().mapToInt(Set::size).sum();
        LOGGER.info("Generated material mappings: {} tiers, {} total materials", generated.size(), totalMaterials);
        for (Map.Entry<String, Set<String>> entry : generated.entrySet()) {
            LOGGER.info("  {}: {} materials", entry.getKey(), entry.getValue().size());
        }
    }

    /**
     * Merges auto-detected materials from TC registry with existing config entries.
     * User additions are never removed — only new materials are added.
     */
    private static void mergeWithRegistry() {
        LOGGER.info("Merging material mappings with TC registry (preserving user additions)...");

        Map<String, Set<String>> fromRegistry = scanRegistryForTiers();

        int addedCount = 0;
        int skippedOverrides = 0;
        for (Map.Entry<String, Set<String>> entry : fromRegistry.entrySet()) {
            String tier = entry.getKey();
            Set<String> registryMaterials = entry.getValue();
            Set<String> existing = MATERIAL_MAPPINGS.computeIfAbsent(tier, k -> new LinkedHashSet<>());

            for (String material : registryMaterials) {
                // Skip if user already placed this material in a different tier (respect user override)
                if (isInDifferentTier(material, tier)) {
                    skippedOverrides++;
                    continue;
                }
                if (existing.add(material)) {
                    addedCount++;
                }
            }
        }

        if (skippedOverrides > 0) {
            LOGGER.info("Skipped {} materials already assigned to a different tier by user", skippedOverrides);
        }

        if (addedCount > 0) {
            saveConfig();
            TinkerMaterialIngredient.clearDisplayCache();
            LOGGER.info("Merge complete: added {} new materials", addedCount);
        } else {
            LOGGER.info("Merge complete: no new materials found (config is up to date)");
        }
    }

    /** Returns true if the material already exists in a tier other than the given one (user override). */
    private static boolean isInDifferentTier(String material, String tier) {
        for (Map.Entry<String, Set<String>> entry : MATERIAL_MAPPINGS.entrySet()) {
            if (!entry.getKey().equals(tier) && entry.getValue().contains(material)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scans TC MaterialRegistry for all visible materials with HeadMaterialStats.
     * Maps each material's harvest tier to a config tier name via TierSortingRegistry.
     * Materials with unknown/modded tiers are skipped with a warning.
     */
    private static Map<String, Set<String>> scanRegistryForTiers() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        IMaterialRegistry registry = MaterialRegistry.getInstance();

        for (IMaterial material : registry.getVisibleMaterials()) {
            MaterialId materialId = material.getIdentifier();
            Optional<HeadMaterialStats> stats = registry.getMaterialStats(materialId, HeadMaterialStats.ID);

            if (stats.isEmpty()) continue;

            Tier tier = stats.get().tier();
            ResourceLocation tierId = TierSortingRegistry.getName(tier);

            if (tierId == null) {
                LOGGER.warn("Could not resolve tier name for material '{}', skipping", materialId);
                continue;
            }

            String configName = TIER_NAME_MAP.get(tierId);
            if (configName == null) {
                LOGGER.warn("Unknown tier '{}' for material '{}', skipping", tierId, materialId);
                continue;
            }

            result.computeIfAbsent(configName, k -> new LinkedHashSet<>()).add(materialId.toString());
        }

        return result;
    }

    /**
     * Saves MATERIAL_MAPPINGS to the config file.
     * Tiers written in consistent order (wooden, stone, iron, golden, diamond, netherite),
     * with any user-defined tiers appended at the end.
     */
    private static void saveConfig() {
        Map<String, List<String>> toSave = new LinkedHashMap<>();

        // Write known tiers in consistent order
        for (String tier : TIER_ORDER) {
            Set<String> materials = MATERIAL_MAPPINGS.get(tier);
            if (materials != null && !materials.isEmpty()) {
                toSave.put(tier, new ArrayList<>(materials));
            }
        }

        // Append any user-defined tiers not in TIER_ORDER
        for (Map.Entry<String, Set<String>> entry : MATERIAL_MAPPINGS.entrySet()) {
            if (!toSave.containsKey(entry.getKey())) {
                toSave.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }

        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(toSave, writer);
            LOGGER.debug("Saved material mappings config to: {}", configFile.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("Failed to save material mappings config", e);
        }
    }

    /**
     * Loads material mappings from the config file into MATERIAL_MAPPINGS.
     */
    private static void loadConfig() {
        MATERIAL_MAPPINGS.clear();

        try (FileReader reader = new FileReader(configFile)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();

            for (String tier : json.keySet()) {
                Set<String> materials = new LinkedHashSet<>();
                json.getAsJsonArray(tier).forEach(element -> materials.add(element.getAsString()));
                MATERIAL_MAPPINGS.put(tier, materials);
                LOGGER.debug("Loaded {} materials for tier '{}'", materials.size(), tier);
            }

            LOGGER.info("Successfully loaded material mappings for {} tiers", MATERIAL_MAPPINGS.size());
        } catch (IOException e) {
            LOGGER.error("Failed to load material mappings config", e);
        } catch (Exception e) {
            LOGGER.error("Error parsing material mappings config", e);
        }
    }

    /**
     * Reloads material mappings from the config file.
     */
    public static void reload() {
        LOGGER.info("Reloading material mappings...");
        loadConfig();
        TinkerMaterialIngredient.clearDisplayCache();
    }

    /**
     * Gets the set of TC material IDs that count as a specific vanilla tier.
     *
     * @param tier The vanilla tier (wooden, stone, iron, golden, diamond, netherite)
     * @return Set of TC material IDs, or null if tier not found
     */
    public static Set<String> getMaterialsForTier(String tier) {
        return MATERIAL_MAPPINGS.get(tier.toLowerCase());
    }

    /**
     * Checks if a specific TC material ID matches a vanilla tier.
     */
    public static boolean isMaterialValidForTier(String tier, String materialId) {
        Set<String> materials = getMaterialsForTier(tier);
        return materials != null && materials.contains(materialId);
    }

    /**
     * Gets all configured tiers.
     */
    public static Set<String> getAllTiers() {
        return MATERIAL_MAPPINGS.keySet();
    }

    /**
     * Gets the config file location.
     */
    public static File getConfigFile() {
        return configFile;
    }
}
