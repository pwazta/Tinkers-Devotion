package com.pwazta.nomorevanillatools.loot;

import com.mojang.logging.LogUtils;
import com.pwazta.nomorevanillatools.config.MaterialMappingConfig;
import com.pwazta.nomorevanillatools.util.TcItemRegistry;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import slimeknights.tconstruct.library.materials.IMaterialRegistry;
import slimeknights.tconstruct.library.materials.MaterialRegistry;
import slimeknights.tconstruct.library.materials.definition.IMaterial;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.materials.stats.MaterialStatsId;
import slimeknights.tconstruct.library.tools.definition.ToolDefinition;
import slimeknights.tconstruct.library.tools.definition.module.material.ToolMaterialHook;
import slimeknights.tconstruct.library.tools.item.IModifiable;
import slimeknights.tconstruct.library.tools.nbt.MaterialNBT;
import slimeknights.tconstruct.library.tools.nbt.ToolStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds randomized Tinker's Construct tools, armor, and ranged weapons for loot replacement.
 * Selects materials using weighted algorithms per part slot.
 *
 * Shared by VanillaLootReplacer (GLM) and MobEquipmentReplacer (spawn event).
 */
public class TinkerToolBuilder {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ── Material selection weights ──────────────────────────────────────

    /** Probability of selecting the canonical (first-in-config) material for head/plating/ranged parts. */
    private static final float CANONICAL_WEIGHT = 0.85f;

    /** Probability threshold for selecting the basic (lowest tier) material for other parts. */
    private static final float OTHER_BASIC_WEIGHT = 0.50f;

    /** Cumulative threshold for basic + same-as-head selection for other parts. (0.80 = 50% basic + 30% same-as-head) */
    private static final float OTHER_SAME_AS_HEAD_WEIGHT = 0.80f;

    /** Maximum IMaterial.getTier() allowed for armor inner parts. Caps at diamond-level to prevent overpowered inner materials. */
    private static final int ARMOR_INNER_MAX_TIER = 3;

    /** Maps vanilla tier name to IMaterial.getTier() int for ranged per-part filtering. */
    private static final Map<String, Integer> TIER_NAME_TO_INT = Map.of(
        "wooden", 0, "stone", 1, "iron", 2, "golden", 1, "diamond", 3, "netherite", 4
    );

    // ── Caches (ConcurrentHashMap, consistent with codebase pattern) ─────

    /** Compatible materials per stat type. Cleared on materials reload. */
    private static final Map<MaterialStatsId, List<IMaterial>> MATERIAL_CACHE = new ConcurrentHashMap<>();

    /** Clears all caches. Called from MaterialMappingConfig and ToolExclusionConfig reload paths. */
    public static void clearCaches() {
        MATERIAL_CACHE.clear();
        TcItemRegistry.clearCaches();
    }

    // ── Main entry point ─────────────────────────────────────────────────

    /**
     * Attempts to replace a vanilla tool/armor/ranged ItemStack with a TC equivalent.
     *
     * @param original the original vanilla ItemStack
     * @param random   random source for material selection
     * @return a TC replacement ItemStack, or null if no replacement applies
     */
    public static @Nullable ItemStack tryReplace(ItemStack original, RandomSource random) {
        if (original.isEmpty()) return null;

        Item item = original.getItem();

        // Check tools
        VanillaItemMappings.ToolInfo toolInfo = VanillaItemMappings.getToolInfo(item);
        if (toolInfo != null) return buildRandomTool(toolInfo.toolType(), toolInfo.tier(), original, random);

        // Check armor
        VanillaItemMappings.ArmorInfo armorInfo = VanillaItemMappings.getArmorInfo(item);
        if (armorInfo != null) return buildRandomArmor(armorInfo.slot(), armorInfo.tier(), original, random);

        // Check ranged weapons
        VanillaItemMappings.RangedInfo rangedInfo = VanillaItemMappings.getRangedInfo(item);
        if (rangedInfo != null) return buildRandomRanged(rangedInfo.rangedType(), rangedInfo.partTiers(), original, random);

        return null;
    }

    // ── Tool building ────────────────────────────────────────────────────

    private static @Nullable ItemStack buildRandomTool(String toolType, String tier, ItemStack original, RandomSource random) {
        try {
            ToolAction action = VanillaItemMappings.getToolAction(toolType);
            if (action == null) return null;

            List<IModifiable> eligible = TcItemRegistry.getEligibleTools(action, toolType);
            if (eligible.isEmpty()) return null;

            IModifiable selected = eligible.get(random.nextInt(eligible.size()));

            ToolDefinition definition = selected.getToolDefinition();
            if (!definition.isDataLoaded()) return null;

            List<MaterialStatsId> statTypes = getStatTypes(selected, definition);
            if (statTypes == null) return null;

            List<MaterialVariantId> materials = selectToolMaterials(tier, statTypes, random);
            if (materials == null) return null;

            return buildFromMaterials(selected, definition, materials, original);
        } catch (Exception e) {
            LOGGER.warn("Failed to build TC tool replacement: {}", e.getMessage());
            return null;
        }
    }

    // ── Armor building ───────────────────────────────────────────────────

    private static @Nullable ItemStack buildRandomArmor(String slot, String tier, ItemStack original, RandomSource random) {
        try {
            ArmorItem.Type armorType = VanillaItemMappings.getArmorType(slot);
            if (armorType == null) return null;

            List<IModifiable> eligible = TcItemRegistry.getEligibleArmor(armorType, slot);
            if (eligible.isEmpty()) return null;

            IModifiable selected = eligible.get(random.nextInt(eligible.size()));

            ToolDefinition definition = selected.getToolDefinition();
            if (!definition.isDataLoaded()) return null;

            List<MaterialStatsId> statTypes = getStatTypes(selected, definition);
            if (statTypes == null) return null;

            List<MaterialVariantId> materials = selectArmorMaterials(tier, statTypes, random);
            if (materials == null) return null;

            return buildFromMaterials(selected, definition, materials, original);
        } catch (Exception e) {
            LOGGER.warn("Failed to build TC armor replacement: {}", e.getMessage());
            return null;
        }
    }

    // ── Shared build logic ───────────────────────────────────────────────

    /** Returns stat types for a tool definition, or null with warning if empty. */
    private static @Nullable List<MaterialStatsId> getStatTypes(IModifiable modifiable, ToolDefinition definition) {
        List<MaterialStatsId> statTypes = ToolMaterialHook.stats(definition);
        if (statTypes.isEmpty()) {
            LOGGER.warn("No material stats for tool definition {}, skipping loot replacement",
                ForgeRegistries.ITEMS.getKey((Item) modifiable));
            return null;
        }
        return statTypes;
    }

    /** Builds a TC item from pre-selected materials. Transfers damage from the original. */
    private static ItemStack buildFromMaterials(IModifiable modifiable, ToolDefinition definition, List<MaterialVariantId> materials, ItemStack original) {
        MaterialNBT.Builder materialsBuilder = MaterialNBT.builder();
        for (MaterialVariantId material : materials) materialsBuilder.add(material);

        ToolStack toolStack = ToolStack.createTool((Item) modifiable, definition, materialsBuilder.build());
        toolStack.rebuildStats();

        ItemStack result = toolStack.createStack();
        transferDamage(original, result);

        // TODO: Enchantment -> TC modifier conversion (P2)
        // Currently enchantments on the original vanilla item are dropped.
        // TC tools use modifiers, not enchantments. Future: map common enchantments
        // to TC modifier equivalents and apply via ToolStack modifier API.

        return result;
    }

    /** Selects materials for a melee tool: head (index 0) + other parts (index 1+). */
    private static @Nullable List<MaterialVariantId> selectToolMaterials(String tier, List<MaterialStatsId> statTypes, RandomSource random) {
        List<MaterialVariantId> materials = new ArrayList<>();

        MaterialVariantId headMaterial = selectHeadMaterial(tier, random, false);
        if (headMaterial == null) return null;
        materials.add(headMaterial);

        IMaterial headMat = MaterialRegistry.getInstance().getMaterial(headMaterial.getId());
        int headTcTier = headMat.getTier();

        for (int i = 1; i < statTypes.size(); i++) {
            MaterialVariantId partMaterial = selectOtherPartMaterial(statTypes.get(i), headTcTier, headMaterial, random);
            if (partMaterial == null) return null;
            materials.add(partMaterial);
        }
        return materials;
    }

    /** Selects materials for armor: plating (index 0) + inner parts (index 1+). */
    private static @Nullable List<MaterialVariantId> selectArmorMaterials(String tier, List<MaterialStatsId> statTypes, RandomSource random) {
        List<MaterialVariantId> materials = new ArrayList<>();

        MaterialVariantId platingMaterial = selectHeadMaterial(tier, random, true);
        if (platingMaterial == null) return null;
        materials.add(platingMaterial);

        IMaterial platingMat = MaterialRegistry.getInstance().getMaterial(platingMaterial.getId());
        int platingTcTier = platingMat.getTier();

        for (int i = 1; i < statTypes.size(); i++) {
            MaterialVariantId partMaterial = selectArmorInnerMaterial(statTypes.get(i), platingTcTier, random);
            if (partMaterial == null) return null;
            materials.add(partMaterial);
        }
        return materials;
    }

    // ── Ranged weapon building ──────────────────────────────────────────

    private static @Nullable ItemStack buildRandomRanged(String rangedType, List<String> partTiers, ItemStack original, RandomSource random) {
        try {
            List<IModifiable> eligible = TcItemRegistry.getEligibleRanged(rangedType);
            if (eligible.isEmpty()) return null;

            IModifiable selected = eligible.get(random.nextInt(eligible.size()));

            ToolDefinition definition = selected.getToolDefinition();
            if (!definition.isDataLoaded()) return null;

            List<MaterialStatsId> statTypes = getStatTypes(selected, definition);
            if (statTypes == null) return null;

            List<MaterialVariantId> materials = selectRangedMaterials(partTiers, statTypes, random);
            if (materials == null) return null;

            return buildFromMaterials(selected, definition, materials, original);
        } catch (Exception e) {
            LOGGER.warn("Failed to build TC ranged replacement: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Selects materials for a ranged weapon using per-part tier selection.
     * Each part has its own tier from the partTiers array (matches stat type order).
     * If partTiers is shorter than statTypes, remaining parts use the last tier.
     */
    private static @Nullable List<MaterialVariantId> selectRangedMaterials(List<String> partTiers, List<MaterialStatsId> statTypes, RandomSource random) {
        List<MaterialVariantId> materials = new ArrayList<>();
        for (int i = 0; i < statTypes.size(); i++) {
            String tierName = i < partTiers.size() ? partTiers.get(i) : partTiers.get(partTiers.size() - 1);
            MaterialVariantId partMaterial = selectPartByTier(tierName, statTypes.get(i), random);
            if (partMaterial == null) return null;
            materials.add(partMaterial);
        }
        return materials;
    }

    /**
     * Selects a material for a single part slot, filtered by tier name.
     * 85% canonical (from MaterialMappingConfig), 15% random from tier-filtered pool.
     * Falls back to lowest-tier material if canonical doesn't have this stat type.
     */
    private static @Nullable MaterialVariantId selectPartByTier(String tierName, MaterialStatsId statType, RandomSource random) {
        Integer maxTcTier = TIER_NAME_TO_INT.get(tierName.toLowerCase());
        if (maxTcTier == null) return null;

        List<IMaterial> compatible = getCompatibleMaterials(statType);
        if (compatible.isEmpty()) return null;

        List<IMaterial> filtered = compatible.stream()
            .filter(mat -> mat.getTier() <= maxTcTier)
            .toList();

        // Fallback to all compatible materials if tier filtering leaves nothing
        List<IMaterial> pool = filtered.isEmpty() ? compatible : filtered;

        if (random.nextFloat() < CANONICAL_WEIGHT) {
            // Try canonical material for this tier
            String canonicalId = MaterialMappingConfig.getCanonicalToolMaterial(tierName);
            if (canonicalId != null) {
                MaterialVariantId canonicalVariant = MaterialVariantId.tryParse(canonicalId);
                if (canonicalVariant != null) {
                    boolean inPool = pool.stream()
                        .anyMatch(mat -> mat.getIdentifier().equals(canonicalVariant.getId()));
                    if (inPool) return canonicalVariant;
                }
            }
            // Canonical not compatible with this stat type — use lowest-tier from pool
            IMaterial lowest = pool.get(0);
            for (IMaterial mat : pool) {
                if (mat.getTier() < lowest.getTier()) lowest = mat;
            }
            return MaterialVariantId.create(lowest.getIdentifier(), "");
        }

        // 15% random from pool
        IMaterial selected = pool.get(random.nextInt(pool.size()));
        return MaterialVariantId.create(selected.getIdentifier(), "");
    }

    // ── Head / Plating selection (index 0) ───────────────────────────────

    /**
     * Selects head/plating material using weighted algorithm:
     * 85% canonical (from MaterialMappingConfig), 15% random from tier pool.
     * Canonical materials match JEI display — defined in MaterialMappingConfig.
     */
    private static @Nullable MaterialVariantId selectHeadMaterial(String tier, RandomSource random, boolean isArmor) {
        Set<String> materials = isArmor
            ? MaterialMappingConfig.getArmorMaterialsForTier(tier)
            : MaterialMappingConfig.getMaterialsForTier(tier);
        if (materials == null || materials.isEmpty()) return null;

        String selectedId;
        if (random.nextFloat() < CANONICAL_WEIGHT) {
            // Canonical material — same as JEI display, with fallback to first in config
            selectedId = isArmor
                ? MaterialMappingConfig.getCanonicalArmorMaterial(tier)
                : MaterialMappingConfig.getCanonicalToolMaterial(tier);
        } else {
            // Random from tier pool
            selectedId = materials.stream().skip(random.nextInt(materials.size())).findFirst().orElse(null);
        }
        if (selectedId == null) return null;

        return MaterialVariantId.tryParse(selectedId);
    }

    // ── Tool other parts (index 1+) — same TC tier or lower ──────────────

    /**
     * Selects material for a non-head tool part using weighted algorithm:
     * 50% basic (lowest tier), 30% same as head, 20% random (same tier or lower).
     *
     * Uses IMaterial.getTier() (int 0-4) for filtering, NOT HeadMaterialStats.tier().
     */
    private static @Nullable MaterialVariantId selectOtherPartMaterial(MaterialStatsId statType,
            int headTcTier, MaterialVariantId headMaterial, RandomSource random) {
        List<IMaterial> compatible = getCompatibleMaterials(statType);
        if (compatible.isEmpty()) return null;

        List<IMaterial> filtered = compatible.stream()
            .filter(mat -> mat.getTier() <= headTcTier)
            .toList();

        // Fallback to all compatible materials if tier filtering leaves nothing
        List<IMaterial> pool = filtered.isEmpty() ? compatible : filtered;

        // Find basic (lowest tier) material
        IMaterial basic = pool.get(0);
        for (IMaterial mat : pool) {
            if (mat.getTier() < basic.getTier()) basic = mat;
        }

        // Check if head material is compatible with this stat type
        boolean headCompatible = compatible.stream()
            .anyMatch(mat -> mat.getIdentifier().equals(headMaterial.getId()));

        float roll = random.nextFloat();
        if (roll < OTHER_BASIC_WEIGHT) {
            return MaterialVariantId.create(basic.getIdentifier(), "");
        } else if (roll < OTHER_SAME_AS_HEAD_WEIGHT) {
            // Same as head if compatible, else basic
            if (headCompatible) return headMaterial;
            return MaterialVariantId.create(basic.getIdentifier(), "");
        } else {
            IMaterial selected = pool.get(random.nextInt(pool.size()));
            return MaterialVariantId.create(selected.getIdentifier(), "");
        }
    }

    // ── Armor inner parts (index 1+) — tier-filtered, capped at diamond ──

    /**
     * Selects material for armor inner part (maille, shield_core, etc.).
     * Uniform random from compatible materials, filtered to head tier or lower,
     * hard-capped at ARMOR_INNER_MAX_TIER (3 = diamond) to prevent overpowered inner materials.
     * Falls back to unfiltered compatible materials if tier filtering leaves an empty pool.
     */
    private static @Nullable MaterialVariantId selectArmorInnerMaterial(MaterialStatsId statType, int headTcTier, RandomSource random) {
        List<IMaterial> compatible = getCompatibleMaterials(statType);
        if (compatible.isEmpty()) return null;

        int maxTier = Math.min(headTcTier, ARMOR_INNER_MAX_TIER);
        List<IMaterial> filtered = compatible.stream()
            .filter(mat -> mat.getTier() <= maxTier)
            .toList();

        // Fallback to all compatible materials if tier filtering leaves nothing
        List<IMaterial> pool = filtered.isEmpty() ? compatible : filtered;
        IMaterial selected = pool.get(random.nextInt(pool.size()));
        return MaterialVariantId.create(selected.getIdentifier(), "");
    }

    // ── Damage transfer ──────────────────────────────────────────────────

    /** Transfers damage proportionally from original to replacement. */
    private static void transferDamage(ItemStack original, ItemStack replacement) {
        if (!original.isDamaged() || original.getMaxDamage() <= 0 || replacement.getMaxDamage() <= 0) return;

        float ratio = (float) original.getDamageValue() / original.getMaxDamage();
        int newDamage = Math.min((int) (ratio * replacement.getMaxDamage()), replacement.getMaxDamage() - 1);
        replacement.setDamageValue(newDamage);
    }

    // ── Compatible materials per stat type (cached) ──────────────────────

    /** Gets all materials compatible with a given stat type. */
    private static List<IMaterial> getCompatibleMaterials(MaterialStatsId statType) {
        return MATERIAL_CACHE.computeIfAbsent(statType, k -> {
            IMaterialRegistry registry = MaterialRegistry.getInstance();
            List<IMaterial> compatible = new ArrayList<>();
            for (IMaterial material : registry.getAllMaterials()) {
                if (registry.getMaterialStats(material.getIdentifier(), statType).isPresent()) {
                    compatible.add(material);
                }
            }
            return compatible;
        });
    }
}
