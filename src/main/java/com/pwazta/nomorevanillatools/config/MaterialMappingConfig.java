package com.pwazta.nomorevanillatools.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.pwazta.nomorevanillatools.loot.TinkerToolBuilder;
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
import java.util.function.Supplier;

/**
 * Maps vanilla material tiers to Tinker's Construct material IDs.
 * Manages both tool and armor mappings via shared TierMappingStore instances.
 *
 * Two-phase initialization per store:
 *   Phase 1 (commonSetup): load existing config file if present
 *   Phase 2 (MaterialsLoadedEvent): generate from registry if config is missing, or merge if force-regenerate
 *
 * The generate command uses refresh methods which combine disk reload + registry merge.
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

    /** Materials with PlatingMaterialStats but NO HeadMaterialStats — need hardcoded tier defaults. */
    private static final Map<String, String> ARMOR_ONLY_DEFAULTS = Map.of(
        "tconstruct:gold",     "golden",
        "tconstruct:obsidian", "diamond",
        "tconstruct:aluminum", "diamond"
    );

    /** Materials whose head tier doesn't match their appropriate armor tier. */
    private static final Map<String, String> ARMOR_TIER_OVERRIDES = Map.of(
        "tconstruct:copper", "leather"  // copper plating defense (1/2/3/1) = vanilla leather
    );

    // ── Store instances ───────────────────────────────────────────────

    private static final TierMappingStore TOOL_STORE = new TierMappingStore(
        List.of("wooden", "stone", "iron", "golden", "diamond", "netherite"),
        "tool", MaterialMappingConfig::scanToolRegistry
    );

    private static final TierMappingStore ARMOR_STORE = new TierMappingStore(
        List.of("leather", "wooden", "stone", "iron", "golden", "diamond", "netherite"),
        "armor", MaterialMappingConfig::scanArmorRegistry
    );

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
     * Manages a single tier-to-materials mapping with config file persistence and registry scanning.
     * Tool and armor each get their own instance with different tier orders and scan logic.
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

    /** Scans for materials with PlatingMaterialStats. Uses overrides/defaults for materials without HeadMaterialStats. */
    private static RegistryScanResult scanArmorRegistry() {
        Map<String, Set<String>> tiers = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        IMaterialRegistry registry = MaterialRegistry.getInstance();

        for (IMaterial material : registry.getVisibleMaterials()) {
            MaterialId materialId = material.getIdentifier();
            String matIdStr = materialId.toString();

            // Only include materials with plating stats (checking BOOTS is sufficient —
            // addArmorShieldStats adds all slots atomically)
            Optional<PlatingMaterialStats> plating = registry.getMaterialStats(materialId, PlatingMaterialStats.BOOTS.getId());
            if (plating.isEmpty()) continue;

            // 1. Check overrides first (copper → leather)
            String overrideTier = ARMOR_TIER_OVERRIDES.get(matIdStr);
            if (overrideTier != null) {
                tiers.computeIfAbsent(overrideTier, k -> new LinkedHashSet<>()).add(matIdStr);
                continue;
            }

            // 2. Check armor-only defaults (gold, obsidian, aluminum — no HeadMaterialStats)
            String defaultTier = ARMOR_ONLY_DEFAULTS.get(matIdStr);
            if (defaultTier != null) {
                tiers.computeIfAbsent(defaultTier, k -> new LinkedHashSet<>()).add(matIdStr);
                continue;
            }

            // 3. Fall back to HeadMaterialStats tier
            Optional<HeadMaterialStats> headStats = registry.getMaterialStats(materialId, HeadMaterialStats.ID);
            if (headStats.isEmpty()) {
                LOGGER.warn("Armor material '{}' has plating stats but no head stats and no hardcoded default, skipping", materialId);
                skipped.add(matIdStr + " (no head stats, no default)");
                continue;
            }

            Tier headTier = headStats.get().tier();
            ResourceLocation tierId = TierSortingRegistry.getName(headTier);

            if (tierId == null) {
                LOGGER.warn("Could not resolve tier name for armor material '{}', skipping", materialId);
                skipped.add(matIdStr + " (tier: unknown)");
                continue;
            }

            String configName = TIER_NAME_MAP.get(tierId);
            if (configName == null) {
                LOGGER.warn("Unknown tier '{}' for armor material '{}', skipping", tierId, materialId);
                skipped.add(matIdStr + " (tier: " + tierId + ")");
                continue;
            }

            tiers.computeIfAbsent(configName, k -> new LinkedHashSet<>()).add(matIdStr);
        }

        return new RegistryScanResult(tiers, skipped);
    }

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

    /** Canonical armor material per vanilla tier. Separate because armor has leather tier and different golden mapping. */
    private static final Map<String, String> CANONICAL_ARMOR_MATERIALS = Map.of(
        "leather",   "tconstruct:copper",
        "iron",      "tconstruct:iron",
        "golden",    "tconstruct:gold",
        "diamond",   "tconstruct:cobalt",
        "netherite", "tconstruct:hepatizon"
    );

    /**
     * Returns the canonical tool material for the given tier.
     * Falls back to the first material in the config if the canonical isn't available.
     */
    public static @Nullable String getCanonicalToolMaterial(String tier) {
        return resolveCanonical(CANONICAL_TOOL_MATERIALS, TOOL_STORE, tier);
    }

    /**
     * Returns the canonical armor material for the given tier.
     * Falls back to the first material in the config if the canonical isn't available.
     */
    public static @Nullable String getCanonicalArmorMaterial(String tier) {
        return resolveCanonical(CANONICAL_ARMOR_MATERIALS, ARMOR_STORE, tier);
    }

    private static @Nullable String resolveCanonical(Map<String, String> canonicalMap, TierMappingStore store, String tier) {
        String canonical = canonicalMap.get(tier.toLowerCase());
        Set<String> materials = store.getMaterialsForTier(tier);
        if (canonical != null && materials != null && materials.contains(canonical)) return canonical;
        return (materials != null && !materials.isEmpty()) ? materials.iterator().next() : null;
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

    // ── Public API — Armor ────────────────────────────────────────────

    public static void initializeArmor(File configDir) { ARMOR_STORE.initialize(configDir, "armor_material_mappings.json"); }
    public static void generateArmorIfNeeded(boolean force) { ARMOR_STORE.generateIfNeeded(force); }
    public static MergeResult refreshArmorFromRegistry() { return ARMOR_STORE.refresh(); }
    public static void reloadArmor() { ARMOR_STORE.reload(); }
    public static Set<String> getArmorMaterialsForTier(String tier) { return ARMOR_STORE.getMaterialsForTier(tier); }
    public static boolean isArmorMaterialValidForTier(String tier, String id) { return ARMOR_STORE.isMaterialValidForTier(tier, id); }
    public static File getArmorConfigFile() { return ARMOR_STORE.configFile; }
}
