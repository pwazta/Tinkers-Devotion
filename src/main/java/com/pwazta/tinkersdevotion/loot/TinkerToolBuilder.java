package com.pwazta.tinkersdevotion.loot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.pwazta.tinkersdevotion.Config;
import com.pwazta.tinkersdevotion.config.TiersToTcMaterials;
import com.pwazta.tinkersdevotion.loot.strategy.ArmorReplacementStrategy;
import com.pwazta.tinkersdevotion.loot.strategy.FishingRodReplacementStrategy;
import com.pwazta.tinkersdevotion.loot.strategy.FlintAndSteelReplacementStrategy;
import com.pwazta.tinkersdevotion.loot.strategy.RangedReplacementStrategy;
import com.pwazta.tinkersdevotion.loot.strategy.ReplacementStrategy;
import com.pwazta.tinkersdevotion.loot.strategy.ShieldReplacementStrategy;
import com.pwazta.tinkersdevotion.loot.strategy.ToolReplacementStrategy;
import com.pwazta.tinkersdevotion.util.TcItemRegistry;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
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

/**
 * Orchestrator for building TC replacements (tools, armor, ranged, shields, fishing rods,
 * flint and steel). Picks an eligible TC item via the strategy and delegates building to
 * {@link ReplacementStrategy#buildReplacement} — material-based strategies share the pipeline
 * exposed via {@link #materialBasedBuild}; material-less strategies override directly.
 *
 * <p>Shared by VanillaLootReplacer (GLM) and MobEquipmentReplacer (spawn event).
 */
public class TinkerToolBuilder {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ── Shared constants (public for strategy sub-package access) ────────

    /** Probability of selecting the canonical (first-in-config) material for primary parts. */
    public static final float CANONICAL_WEIGHT = 0.80f;

    // ── Strategy dispatch ────────────────────────────────────────────────

    private static final Map<Class<? extends VanillaItemMappings.ReplacementInfo>, ReplacementStrategy> STRATEGIES = Map.of(
        VanillaItemMappings.ToolInfo.class,           ToolReplacementStrategy.INSTANCE,
        VanillaItemMappings.ArmorInfo.class,          ArmorReplacementStrategy.INSTANCE,
        VanillaItemMappings.RangedInfo.class,         RangedReplacementStrategy.INSTANCE,
        VanillaItemMappings.ShieldInfo.class,         ShieldReplacementStrategy.INSTANCE,
        VanillaItemMappings.FishingRodInfo.class,     FishingRodReplacementStrategy.INSTANCE,
        VanillaItemMappings.FlintAndSteelInfo.class,  FlintAndSteelReplacementStrategy.INSTANCE
    );

    // ── Caches (ConcurrentHashMap, consistent with codebase pattern) ─────

    /** Compatible materials per stat type. Cleared on materials reload. */
    private static final Map<MaterialStatsId, List<IMaterial>> MATERIAL_CACHE = new ConcurrentHashMap<>();

    /**
     * Clears lazy caches that are safe to rebuild on demand.
     * Tool tier state is intentionally NOT cleared here — it's owned by
     * {@link TiersToTcMaterials#rebuildToolCaches} which only fires on MaterialsLoadedEvent.
     */
    public static void clearCaches() {
        MATERIAL_CACHE.clear();
        TiersToTcMaterials.clearArmorCaches();
        TcItemRegistry.clearCaches();
        EnchantmentConverter.clearCache();
    }

    // ── Main entry point ─────────────────────────────────────────────────

    /**
     * Attempts to replace a vanilla item with a TC equivalent.
     *
     * @param original the original vanilla ItemStack
     * @param random   random source for selection
     * @return a TC replacement ItemStack, or null if no replacement applies
     */
    public static @Nullable ItemStack tryReplace(ItemStack original, RandomSource random) {
        if (original.isEmpty()) return null;

        var info = VanillaItemMappings.getReplacementInfo(original.getItem());
        if (info == null) return null;

        ReplacementStrategy strategy = STRATEGIES.get(info.getClass());
        if (strategy == null) return null;

        try {
            List<IModifiable> eligible = strategy.findEligible(info);
            if (eligible.isEmpty()) return null;
            IModifiable selected = eligible.get(random.nextInt(eligible.size()));
            return strategy.buildReplacement(info, selected, original, random);
        } catch (Exception e) {
            LOGGER.warn("Failed to build TC replacement: {}", e.getMessage());
            return null;
        }
    }

    // ── Shared build logic ───────────────────────────────────────────────

    /**
     * Material-based build pipeline used by {@link ReplacementStrategy#buildReplacement}'s
     * default implementation: resolves stat types, calls {@link ReplacementStrategy#selectMaterials},
     * and assembles the tool. Bails (with a diagnostic warn) if the definition has no
     * material stats — that case is reserved for material-less strategies which override
     * {@code buildReplacement} directly.
     */
    public static @Nullable ItemStack materialBasedBuild(
            ReplacementStrategy strategy, VanillaItemMappings.ReplacementInfo info,
            IModifiable selected, ItemStack original, RandomSource random) {
        ToolDefinition definition = selected.getToolDefinition();
        if (!definition.isDataLoaded()) return null;

        List<MaterialStatsId> statTypes = ToolMaterialHook.stats(definition);
        if (statTypes.isEmpty()) {
            LOGGER.warn("No material stats for tool definition {}, skipping replacement",
                ForgeRegistries.ITEMS.getKey((Item) selected));
            return null;
        }

        List<MaterialVariantId> materials = strategy.selectMaterials(info, statTypes, random);
        if (materials == null) return null;

        return buildFromMaterials(selected, definition, materials, original, random);
    }

    /** Builds a TC item from pre-selected materials, applies modifier conversion, and transfers damage. */
    public static ItemStack buildFromMaterials(IModifiable modifiable, ToolDefinition definition,
            List<MaterialVariantId> materials, ItemStack original, RandomSource random) {
        MaterialNBT.Builder materialsBuilder = MaterialNBT.builder();
        for (MaterialVariantId material : materials) materialsBuilder.add(material);

        ToolStack toolStack = ToolStack.createTool((Item) modifiable, definition, materialsBuilder.build());
        toolStack.rebuildStats();

        EnchantmentConverter.applyModifiers(toolStack, original, random);

        ItemStack result = toolStack.createStack();
        transferDamage(original, result);
        return result;
    }

    // ── Damage transfer ──────────────────────────────────────────────────

    /** Transfers damage proportionally from original to replacement. */
    public static void transferDamage(ItemStack original, ItemStack replacement) {
        if (!original.isDamaged() || original.getMaxDamage() <= 0 || replacement.getMaxDamage() <= 0) return;

        float ratio = (float) original.getDamageValue() / original.getMaxDamage();
        int newDamage = Math.min((int) (ratio * replacement.getMaxDamage()), replacement.getMaxDamage() - 1);
        replacement.setDamageValue(newDamage);
    }

    // ── Single-tier shared selection (used by ranged, shield, and future single-tier strategies) ──

    /**
     * Selects materials for all parts of a single-tier item from canonical material IDs.
     * Each canonical material defines both the 80% default and the tier for variance selection.
     *
     * @param canonicalMaterials per-part canonical material IDs (e.g., ["tconstruct:wood","tconstruct:iron","tconstruct:string"])
     * @param statTypes          stat type IDs per part slot from the tool definition
     * @param random             random source
     * @return ordered list of materials, or null if any part selection fails
     */
    public static @Nullable List<MaterialVariantId> selectMaterialsByCanonicals(List<String> canonicalMaterials, List<MaterialStatsId> statTypes, RandomSource random) {
        List<MaterialVariantId> materials = new ArrayList<>();
        for (int i = 0; i < statTypes.size(); i++) {
            String canonicalId = i < canonicalMaterials.size() ? canonicalMaterials.get(i) : canonicalMaterials.get(canonicalMaterials.size() - 1);
            MaterialVariantId canonical = MaterialVariantId.tryParse(canonicalId);
            if (canonical == null) return null;
            int baseTier = MaterialRegistry.getInstance().getMaterial(canonical.getId()).getTier();
            MaterialVariantId partMaterial = selectByPartTier(baseTier, canonical, statTypes.get(i), random);
            if (partMaterial == null) return null;
            materials.add(partMaterial);
        }
        return materials;
    }

    /**
     * Selects a material for one part slot. 80% canonical, 10% same-tier random, 10% any-tier variance (configurable).
     *
     * @param baseTier  TC material tier int for this part
     * @param canonical canonical material for 80% weight selection
     * @param statType  the stat type ID for this part slot
     * @param random    random source
     * @return selected material, or null if no compatible materials exist
     */
    public static @Nullable MaterialVariantId selectByPartTier(int baseTier, MaterialVariantId canonical, MaterialStatsId statType, RandomSource random) {
        List<IMaterial> compatible = getCompatibleMaterials(statType);
        if (compatible.isEmpty()) return null;

        List<IMaterial> sameTierPool = compatible.stream().filter(mat -> mat.getTier() == baseTier).toList();
        if (sameTierPool.isEmpty()) sameTierPool = compatible;

        float roll = random.nextFloat();

        // 80%: canonical material if compatible with this stat type
        if (roll < CANONICAL_WEIGHT) {
            boolean inPool = sameTierPool.stream().anyMatch(mat -> mat.getIdentifier().equals(canonical.getId()));
            if (inPool) return canonical;
        }

        // 10% (configurable): random from any tier up to maxLootVarianceTier
        if (roll >= 1.0f - (float) Config.lootVarianceChance) {
            List<IMaterial> variancePool = compatible.stream().filter(mat -> mat.getTier() <= Config.maxLootVarianceTier).toList();
            if (variancePool.isEmpty()) variancePool = compatible;
            IMaterial selected = variancePool.get(random.nextInt(variancePool.size()));
            return MaterialVariantId.create(selected.getIdentifier(), "");
        }

        // 10%: random material at exactly the base tier
        IMaterial selected = sameTierPool.get(random.nextInt(sameTierPool.size()));
        return MaterialVariantId.create(selected.getIdentifier(), "");
    }

    // ── Compatible materials per stat type (cached, public for strategies) ──

    /** Gets all materials compatible with a given stat type. */
    public static List<IMaterial> getCompatibleMaterials(MaterialStatsId statType) {
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
