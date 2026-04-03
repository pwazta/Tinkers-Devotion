package com.pwazta.nomorevanillatools.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.pwazta.nomorevanillatools.loot.TinkerToolBuilder;
import com.pwazta.nomorevanillatools.loot.VanillaItemMappings;
import com.pwazta.nomorevanillatools.recipe.TinkerMaterialIngredient;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Tier;
import net.minecraftforge.common.TierSortingRegistry;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import slimeknights.tconstruct.library.materials.IMaterialRegistry;
import slimeknights.tconstruct.library.materials.MaterialRegistry;
import slimeknights.tconstruct.library.materials.definition.IMaterial;
import slimeknights.tconstruct.library.materials.definition.MaterialId;
import slimeknights.tconstruct.tools.stats.HeadMaterialStats;
import slimeknights.tconstruct.tools.stats.PlatingMaterialStats;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Maps vanilla material tiers to Tinker's Construct material IDs.
 *
 * <p><b>Tools</b>: Config-based via TierMappingStore — maps HeadMaterialStats.tier() to vanilla tier names.
 * Two-phase initialization: Phase 1 (commonSetup) loads config, Phase 2 (MaterialsLoadedEvent) generates/merges.
 *
 * <p><b>Armor</b>: Automatic via IMaterial.getTier() — no config file needed. Materials with PlatingMaterialStats
 * are grouped by their IMaterial.getTier() (int 0-4) at query time, cached for performance.
 */
public class MaterialMappingConfig {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Maps TierSortingRegistry ResourceLocations to config tier names. Modded tiers not listed here are skipped. */
    private static final Map<ResourceLocation, String> TIER_NAME_MAP = Map.of(
        new ResourceLocation("minecraft", "wood"),      "wooden",
        new ResourceLocation("minecraft", "stone"),     "stone",
        new ResourceLocation("minecraft", "iron"),      "iron",
        new ResourceLocation("minecraft", "gold"),      "golden",
        new ResourceLocation("minecraft", "diamond"),   "diamond",
        new ResourceLocation("minecraft", "netherite"), "netherite"
    );

    // ── Canonical materials (shared by loot generation + JEI display) ──

    /** Canonical tool material per vanilla tier — the "representative" TC material for loot (85% weight) and JEI display. */
    private static final Map<String, String> CANONICAL_TOOL_MATERIALS = Map.of(
        "wooden",    "tconstruct:wood",
        "stone",     "tconstruct:rock",
        "iron",      "tconstruct:iron",
        "golden",    "tconstruct:rose_gold",
        "diamond",   "tconstruct:cobalt",
        "netherite", "tconstruct:hepatizon"
    );

    /** Canonical armor material per tier range. Unified across all armor sets. Key format: "minTier-maxTier". */
    private static final Map<String, String> CANONICAL_ARMOR_BY_TIER = Map.of(
        "0-1", "tconstruct:copper",
        "2-2", "tconstruct:iron",
        "3-3", "tconstruct:cobalt"
    );

    // ── Store instances ───────────────────────────────────────────────

    private static final TierMappingStore TOOL_STORE = new TierMappingStore(
        Arrays.asList(VanillaItemMappings.ALL_TOOL_TIERS),
        "tool", MaterialMappingConfig::scanToolRegistry
    );

    // ── Armor plating cache (keyed by "minTier-maxTier") ────────────

    private static final Map<String, List<IMaterial>> PLATING_TIER_CACHE = new ConcurrentHashMap<>();

    // ── Result types ──────────────────────────────────────────────────

    /** Result of a registry scan: mapped tiers + materials that couldn't be mapped. */
    private static class RegistryScanResult {
        final Map<String, Set<String>> tiers;
        final List<String> skippedMaterials;

        RegistryScanResult(Map<String, Set<String>> tiers, List<String> skippedMaterials) {
            this.tiers = tiers;
            this.skippedMaterials = skippedMaterials;
        }
    }

    /** Result of a material merge operation, returned to the command for player feedback. */
    public static class MergeResult {
        public final int addedCount;
        public final int skippedOverrides;
        public final List<String> skippedTiers;

        MergeResult(int addedCount, int skippedOverrides, List<String> skippedTiers) {
            this.addedCount = addedCount;
            this.skippedOverrides = skippedOverrides;
            this.skippedTiers = skippedTiers;
        }
    }

    // ── TierMappingStore ──────────────────────────────────────────────

    /**
     * Manages a tier-to-materials mapping with config file persistence and registry scanning.
     * Used for tool materials only — armor uses IMaterial.getTier() directly via {@link #getPlatingMaterialsInTierRange}.
     */
    private static class TierMappingStore {
        private final Map<String, Set<String>> mappings = new HashMap<>();
        private final List<String> tierOrder;
        private final String label;
        private final Supplier<RegistryScanResult> scanner;
        private File configFile;
        private boolean initialized = false;

        TierMappingStore(List<String> tierOrder, String label, Supplier<RegistryScanResult> scanner) {
            this.tierOrder = tierOrder;
            this.label = label;
            this.scanner = scanner;
        }

        /** Phase 1: Sets up config path and loads existing config if present. */
        void initialize(File configDir, String filename) {
            if (initialized) return;

            File modConfigDir = new File(configDir, "nomorevanillatools");
            if (!modConfigDir.exists()) modConfigDir.mkdirs();

            configFile = new File(modConfigDir, filename);
            if (configFile.exists()) {
                LOGGER.info("Loading {} material mappings from: {}", label, configFile.getAbsolutePath());
                load();
            } else {
                LOGGER.info("No {} material mappings config found — will auto-generate when materials load", label);
            }

            initialized = true;
        }

        /** Phase 2: Generates from registry if empty, or merges if force-regenerate. */
        void generateIfNeeded(boolean forceRegenerate) {
            if (configFile == null) {
                LOGGER.error("{} MaterialMappingConfig not initialized, cannot generate", label);
                return;
            }
            if (!MaterialRegistry.isFullyLoaded()) {
                LOGGER.warn("TC MaterialRegistry not fully loaded, skipping {} material auto-generation", label);
                return;
            }

            if (mappings.isEmpty()) {
                generateFresh();
            } else if (forceRegenerate) {
                merge();
            }
        }

        /** Reloads config from disk, then merges with TC registry. Command entry point. */
        MergeResult refresh() {
            if (!MaterialRegistry.isFullyLoaded()) {
                LOGGER.warn("TC MaterialRegistry not fully loaded, cannot refresh {} materials", label);
                return null;
            }

            load();

            if (mappings.isEmpty()) {
                RegistryScanResult scan = scanner.get();
                if (scan.tiers.isEmpty()) {
                    LOGGER.warn("No {} materials found in TC registry", label);
                    return new MergeResult(0, 0, scan.skippedMaterials);
                }

                mappings.putAll(scan.tiers);
                save();
                TinkerMaterialIngredient.clearDisplayCache();
                TinkerToolBuilder.clearCaches();

                int total = scan.tiers.values().stream().mapToInt(Set::size).sum();
                LOGGER.info("Generated {} material mappings: {} tiers, {} total materials", label, scan.tiers.size(), total);
                return new MergeResult(total, 0, scan.skippedMaterials);
            }

            return merge();
        }

        /** Reloads from disk only (no registry scan). */
        void reload() {
            LOGGER.info("Reloading {} material mappings...", label);
            load();
            TinkerMaterialIngredient.clearDisplayCache();
            TinkerToolBuilder.clearCaches();
        }

        Set<String> getMaterialsForTier(String tier) { return mappings.get(tier.toLowerCase()); }

        boolean isMaterialValidForTier(String tier, String materialId) {
            Set<String> materials = getMaterialsForTier(tier);
            return materials != null && materials.contains(materialId);
        }

        // ── Private lifecycle ─────────────────────────────────────────

        private void generateFresh() {
            LOGGER.info("Auto-generating {} material mappings from TC registry...", label);

            RegistryScanResult scan = scanner.get();
            if (scan.tiers.isEmpty()) {
                LOGGER.warn("No {} materials found — config not generated", label);
                return;
            }

            mappings.clear();
            mappings.putAll(scan.tiers);
            save();
            TinkerMaterialIngredient.clearDisplayCache();
            TinkerToolBuilder.clearCaches();

            int total = scan.tiers.values().stream().mapToInt(Set::size).sum();
            LOGGER.info("Generated {} material mappings: {} tiers, {} total materials", label, scan.tiers.size(), total);
            for (Map.Entry<String, Set<String>> entry : scan.tiers.entrySet()) {
                LOGGER.info("  {}: {} materials", entry.getKey(), entry.getValue().size());
            }
        }

        private MergeResult merge() {
            LOGGER.info("Merging {} material mappings with TC registry...", label);

            RegistryScanResult scan = scanner.get();
            int addedCount = 0;
            int skippedOverrides = 0;

            for (Map.Entry<String, Set<String>> entry : scan.tiers.entrySet()) {
                String tier = entry.getKey();
                Set<String> existing = mappings.computeIfAbsent(tier, k -> new LinkedHashSet<>());

                for (String material : entry.getValue()) {
                    if (isInDifferentTier(material, tier)) {
                        skippedOverrides++;
                    } else if (existing.add(material)) {
                        addedCount++;
                    }
                }
            }

            if (skippedOverrides > 0) {
                LOGGER.info("Skipped {} {} materials already in a different user-assigned tier", skippedOverrides, label);
            }

            if (addedCount > 0) {
                save();
                TinkerMaterialIngredient.clearDisplayCache();
                TinkerToolBuilder.clearCaches();
                LOGGER.info("Merge complete ({}): added {} new materials", label, addedCount);
            } else {
                LOGGER.info("Merge complete ({}): config is up to date", label);
            }

            return new MergeResult(addedCount, skippedOverrides, scan.skippedMaterials);
        }

        private boolean isInDifferentTier(String material, String tier) {
            for (Map.Entry<String, Set<String>> entry : mappings.entrySet()) {
                if (!entry.getKey().equals(tier) && entry.getValue().contains(material)) {
                    return true;
                }
            }
            return false;
        }

        private void save() {
            Map<String, List<String>> toSave = new LinkedHashMap<>();

            for (String tier : tierOrder) {
                Set<String> materials = mappings.get(tier);
                if (materials != null && !materials.isEmpty()) {
                    toSave.put(tier, new ArrayList<>(materials));
                }
            }

            for (Map.Entry<String, Set<String>> entry : mappings.entrySet()) {
                if (!toSave.containsKey(entry.getKey())) {
                    toSave.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                }
            }

            try (FileWriter writer = new FileWriter(configFile)) {
                GSON.toJson(toSave, writer);
                LOGGER.debug("Saved {} material mappings to: {}", label, configFile.getAbsolutePath());
            } catch (IOException e) {
                LOGGER.error("Failed to save {} material mappings config", label, e);
            }
        }

        private void load() {
            mappings.clear();
            if (configFile == null || !configFile.exists()) return;

            try (FileReader reader = new FileReader(configFile)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();

                for (String tier : json.keySet()) {
                    Set<String> materials = new LinkedHashSet<>();
                    json.getAsJsonArray(tier).forEach(el -> materials.add(el.getAsString()));
                    mappings.put(tier, materials);
                    LOGGER.debug("Loaded {} {} materials for tier '{}'", materials.size(), label, tier);
                }

                LOGGER.info("Loaded {} material mappings for {} tiers", label, mappings.size());
            } catch (IOException e) {
                LOGGER.error("Failed to load {} material mappings config", label, e);
            } catch (Exception e) {
                LOGGER.error("Error parsing {} material mappings config — will regenerate", label, e);
            }
        }
    }

    // ── Scan methods (genuinely different per store) ──────────────────

    /** Scans for materials with HeadMaterialStats, maps harvest tier to config tier name. */
    private static RegistryScanResult scanToolRegistry() {
        Map<String, Set<String>> tiers = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        IMaterialRegistry registry = MaterialRegistry.getInstance();

        for (IMaterial material : registry.getVisibleMaterials()) {
            MaterialId materialId = material.getIdentifier();
            Optional<HeadMaterialStats> stats = registry.getMaterialStats(materialId, HeadMaterialStats.ID);
            if (stats.isEmpty()) continue;

            Tier tier = stats.get().tier();
            ResourceLocation tierId = TierSortingRegistry.getName(tier);

            if (tierId == null) {
                LOGGER.warn("Could not resolve tier name for material '{}', skipping", materialId);
                skipped.add(materialId + " (tier: unknown)");
                continue;
            }

            String configName = TIER_NAME_MAP.get(tierId);
            if (configName == null) {
                LOGGER.warn("Unknown tier '{}' for material '{}', skipping", tierId, materialId);
                skipped.add(materialId + " (tier: " + tierId + ")");
                continue;
            }

            tiers.computeIfAbsent(configName, k -> new LinkedHashSet<>()).add(materialId.toString());
        }

        return new RegistryScanResult(tiers, skipped);
    }

    // ── Canonical resolution ─────────────────────────────────────────

    /**
     * Returns the canonical tool/ranged material for the given tier.
     * Validates the hardcoded canonical against the user's config; falls back to first-in-config if removed.
     */
    public static @Nullable String getCanonicalToolMaterial(String tier) {
        String canonical = CANONICAL_TOOL_MATERIALS.get(tier.toLowerCase());
        Set<String> materials = TOOL_STORE.getMaterialsForTier(tier);
        if (canonical != null && materials != null && materials.contains(canonical)) return canonical;
        return (materials != null && !materials.isEmpty()) ? materials.iterator().next() : null;
    }

    /**
     * Returns the canonical armor material for a given tier range.
     * Unified across all armor sets. Used for 85% loot weight and JEI display.
     * Returns null if no canonical defined for this tier range.
     */
    public static @Nullable String getCanonicalArmorMaterial(int minTier, int maxTier) {
        return CANONICAL_ARMOR_BY_TIER.get(minTier + "-" + maxTier);
    }

    /**
     * Returns all visible materials with PlatingMaterialStats whose IMaterial.getTier() is in [minTier, maxTier].
     * Results are cached per tier range; cleared via {@link #clearArmorCaches()}.
     */
    public static List<IMaterial> getPlatingMaterialsInTierRange(int minTier, int maxTier) {
        return PLATING_TIER_CACHE.computeIfAbsent(minTier + "-" + maxTier, k -> {
            IMaterialRegistry registry = MaterialRegistry.getInstance();
            List<IMaterial> result = new ArrayList<>();
            for (IMaterial material : registry.getVisibleMaterials()) {
                int tier = material.getTier();
                if (tier < minTier || tier > maxTier) continue;
                if (registry.getMaterialStats(material.getIdentifier(), PlatingMaterialStats.BOOTS.getId()).isEmpty()) continue;
                result.add(material);
            }
            return result;
        });
    }

    /** Clears cached armor plating materials. Called from TinkerToolBuilder.clearCaches(). */
    public static void clearArmorCaches() {
        PLATING_TIER_CACHE.clear();
    }

    // ── Public API — Tool ─────────────────────────────────────────────

    public static void initialize(File configDir) { TOOL_STORE.initialize(configDir, "material_mappings.json"); }
    public static void generateIfNeeded(boolean force) { TOOL_STORE.generateIfNeeded(force); }
    public static MergeResult refreshFromRegistry() { return TOOL_STORE.refresh(); }
    public static void reload() { TOOL_STORE.reload(); }
    public static Set<String> getMaterialsForTier(String tier) { return TOOL_STORE.getMaterialsForTier(tier); }
    public static boolean isMaterialValidForTier(String tier, String id) { return TOOL_STORE.isMaterialValidForTier(tier, id); }
    public static Set<String> getAllTiers() { return TOOL_STORE.mappings.keySet(); }
    public static File getConfigFile() { return TOOL_STORE.configFile; }

}
