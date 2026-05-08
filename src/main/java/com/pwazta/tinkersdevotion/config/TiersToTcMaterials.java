package com.pwazta.tinkersdevotion.config;

import com.mojang.logging.LogUtils;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves vanilla tiers to pools of compatible TC materials, and vice versa. Live cache, no disk state.
 * Used by loot replacement (material selection), recipe matching (tier validation), and JEI display (canonical lookup).
 *
 * <p><b>Tools</b>: cache rebuilt on {@code MaterialsLoadedEvent} via {@link VanillaTier} lookup.
 * Immutable snapshots swapped atomically via volatile write — readers never see partial state.
 *
 * <p><b>Armor</b>: lazy cache per tier range via {@link IMaterial#getTier()}, cleared on reload.
 */
public class TiersToTcMaterials {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Canonical armor material per tier range. Unified across all armor sets. Key format: "minTier-maxTier". */
    private static final Map<String, String> CANONICAL_ARMOR_BY_TIER = Map.of(
        "0-1", "tconstruct:copper",
        "1-2", "tconstruct:gold",
        "2-2", "tconstruct:iron",
        "3-3", "tconstruct:cobalt",
        "4-4", "tconstruct:hepatizon"
    );

    // ── Tool caches (immutable snapshots, swapped atomically on MaterialsLoadedEvent) ────

    /** materialId → tier name (e.g. "tconstruct:iron" → "iron"). Immutable snapshot; replaced via volatile write. */
    private static volatile Map<String, String> toolTierByMaterial = Map.of();

    /** tier name → immutable set of material ids. Immutable snapshot; replaced via volatile write. */
    private static volatile Map<String, Set<String>> toolMaterialsByTier = Map.of();

    /** Materials dropped during last rebuild because their vanilla Tier isn't in {@link VanillaTier}. Diagnostic feedback only — these are NOT user exclusions. */
    private static volatile List<String> unmappedToolMaterials = List.of();

    // ── Armor plating cache (lazy per tier range) ─────────────────────

    private static final Map<String, List<IMaterial>> PLATING_TIER_CACHE = new ConcurrentHashMap<>();

    // ── Tool API ──────────────────────────────────────────────────────

    /**
     * Rebuilds tool tier caches from the live TC registry. Called from {@code MaterialsLoadedEvent}.
     * Builds new maps locally, then publishes via volatile reference assignment — readers see
     * either the old snapshot or the new one, never an intermediate state.
     */
    public static void rebuildToolCaches() {
        if (!MaterialRegistry.isFullyLoaded()) {
            LOGGER.warn("TC MaterialRegistry not fully loaded, skipping tool tier cache rebuild");
            return;
        }

        Map<String, String> newByMaterial = new HashMap<>();
        Map<String, Set<String>> mutableByTier = new LinkedHashMap<>();
        List<String> newUnmapped = new ArrayList<>();

        IMaterialRegistry registry = MaterialRegistry.getInstance();
        for (IMaterial material : registry.getVisibleMaterials()) {
            MaterialId materialId = material.getIdentifier();
            Optional<HeadMaterialStats> stats = registry.getMaterialStats(materialId, HeadMaterialStats.ID);
            if (stats.isEmpty()) continue;

            Tier tier = stats.get().tier();
            ResourceLocation tierId = TierSortingRegistry.getName(tier);
            if (tierId == null) {
                newUnmapped.add(materialId + " (tier: unknown)");
                continue;
            }

            VanillaTier vanillaTier = VanillaTier.fromResourceLocation(tierId);
            String tierName = vanillaTier != null ? vanillaTier.itemPrefix() : null;
            if (tierName == null) {
                newUnmapped.add(materialId + " (tier: " + tierId + ")");
                continue;
            }

            String idStr = materialId.toString();
            newByMaterial.put(idStr, tierName);
            mutableByTier.computeIfAbsent(tierName, k -> new LinkedHashSet<>()).add(idStr);
        }

        // Freeze per-tier sets, then publish both maps via volatile write.
        Map<String, Set<String>> frozenByTier = new LinkedHashMap<>();
        mutableByTier.forEach((k, v) -> frozenByTier.put(k, Set.copyOf(v)));

        toolTierByMaterial = Map.copyOf(newByMaterial);
        toolMaterialsByTier = Map.copyOf(frozenByTier);
        unmappedToolMaterials = List.copyOf(newUnmapped);

        LOGGER.info("Rebuilt tool tier cache: {} materials across {} tiers ({} unmapped)",
            newByMaterial.size(), frozenByTier.size(), newUnmapped.size());
    }

    /** Returns the tier name (e.g. "iron") for a TC material id, or null if the material has no head stats or unknown tier. */
    public static @Nullable String getToolTierName(String materialId) {
        return toolTierByMaterial.get(materialId);
    }

    /** Returns all material ids mapped to the given tier name. Empty set if tier unknown or cache not yet built. */
    public static Set<String> getToolMaterialsForTier(String tier) {
        Set<String> materials = toolMaterialsByTier.get(tier.toLowerCase(Locale.ROOT));
        return materials != null ? materials : Set.of();
    }

    /**
     * Materials dropped from the last tool cache rebuild because their vanilla Tier is unknown or
     * not in {@link VanillaTier}. NOT user exclusions — these are materials the mod literally cannot bucket.
     * Surfaced via {@code /tinkersdevotion generate} as a diagnostic hint for addon-pack authors.
     */
    public static List<String> getUnmappedToolMaterials() {
        return unmappedToolMaterials;
    }

    /**
     * Returns the canonical tool material id for a tier, validated against the live cache.
     * Falls back to the first material in the tier pool if the curated canonical is absent.
     */
    public static @Nullable String getCanonicalToolMaterial(String tier) {
        String tierKey = tier.toLowerCase(Locale.ROOT);
        VanillaTier vt = VanillaTier.fromItemPrefix(tierKey);
        String canonical = vt != null ? vt.canonicalToolMaterial() : null;
        Set<String> pool = toolMaterialsByTier.get(tierKey);
        if (canonical != null && pool != null && pool.contains(canonical)) return canonical;
        return (pool != null && !pool.isEmpty()) ? pool.iterator().next() : null;
    }

    // ── Armor API ─────────────────────────────────────────────────────

    /** Returns curated canonical armor material id for a tier range. No validation — call-site handles missing. */
    public static @Nullable String getCanonicalArmorMaterial(int minTier, int maxTier) {
        return CANONICAL_ARMOR_BY_TIER.get(minTier + "-" + maxTier);
    }

    /**
     * Returns all visible materials with {@link PlatingMaterialStats} whose {@link IMaterial#getTier()}
     * falls in [minTier, maxTier]. Cached per tier range, cleared via {@link #clearArmorCaches}.
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

    /** Clears cached armor plating materials. Called from {@link com.pwazta.tinkersdevotion.loot.TinkerToolBuilder#clearCaches}. */
    public static void clearArmorCaches() {
        PLATING_TIER_CACHE.clear();
    }
}
