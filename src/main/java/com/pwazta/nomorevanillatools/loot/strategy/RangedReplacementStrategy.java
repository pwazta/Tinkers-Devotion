package com.pwazta.nomorevanillatools.loot.strategy;

import com.pwazta.nomorevanillatools.config.MaterialMappingConfig;
import com.pwazta.nomorevanillatools.loot.TinkerToolBuilder;
import com.pwazta.nomorevanillatools.loot.VanillaItemMappings;
import com.pwazta.nomorevanillatools.util.TcItemRegistry;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;
import slimeknights.tconstruct.library.materials.definition.IMaterial;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.materials.stats.MaterialStatsId;
import slimeknights.tconstruct.library.tools.item.IModifiable;

import java.util.ArrayList;
import java.util.List;

/**
 * Strategy for replacing vanilla ranged weapons (bow, crossbow) with TC equivalents.
 */
public final class RangedReplacementStrategy implements ReplacementStrategy {

    public static final RangedReplacementStrategy INSTANCE = new RangedReplacementStrategy();

    private RangedReplacementStrategy() {}

    @Override
    public List<IModifiable> findEligible(VanillaItemMappings.ReplacementInfo info) {
        VanillaItemMappings.RangedInfo rangedInfo = (VanillaItemMappings.RangedInfo) info;
        return TcItemRegistry.getEligibleRanged(rangedInfo.rangedType());
    }

    @Override
    public @Nullable List<MaterialVariantId> selectMaterials(
            VanillaItemMappings.ReplacementInfo info,
            List<MaterialStatsId> statTypes,
            RandomSource random) {
        VanillaItemMappings.RangedInfo rangedInfo = (VanillaItemMappings.RangedInfo) info;
        List<String> partTiers = rangedInfo.partTiers();

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
     */
    private static @Nullable MaterialVariantId selectPartByTier(String tierName, MaterialStatsId statType, RandomSource random) {
        Integer maxTcTier = TinkerToolBuilder.TIER_NAME_TO_INT.get(tierName.toLowerCase());
        if (maxTcTier == null) return null;

        List<IMaterial> compatible = TinkerToolBuilder.getCompatibleMaterials(statType);
        if (compatible.isEmpty()) return null;

        List<IMaterial> filtered = compatible.stream()
            .filter(mat -> mat.getTier() <= maxTcTier)
            .toList();

        List<IMaterial> pool = filtered.isEmpty() ? compatible : filtered;

        if (random.nextFloat() < TinkerToolBuilder.CANONICAL_WEIGHT) {
            String canonicalId = MaterialMappingConfig.getCanonicalToolMaterial(tierName);
            if (canonicalId != null) {
                MaterialVariantId canonicalVariant = MaterialVariantId.tryParse(canonicalId);
                if (canonicalVariant != null) {
                    boolean inPool = pool.stream()
                        .anyMatch(mat -> mat.getIdentifier().equals(canonicalVariant.getId()));
                    if (inPool) return canonicalVariant;
                }
            }
            IMaterial lowest = pool.get(0);
            for (IMaterial mat : pool) {
                if (mat.getTier() < lowest.getTier()) lowest = mat;
            }
            return MaterialVariantId.create(lowest.getIdentifier(), "");
        }

        IMaterial selected = pool.get(random.nextInt(pool.size()));
        return MaterialVariantId.create(selected.getIdentifier(), "");
    }
}
