package com.pwazta.nomorevanillatools.loot.strategy;

import com.pwazta.nomorevanillatools.loot.TinkerToolBuilder;
import com.pwazta.nomorevanillatools.loot.VanillaItemMappings;
import com.pwazta.nomorevanillatools.util.TcItemRegistry;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.materials.stats.MaterialStatsId;
import slimeknights.tconstruct.library.tools.item.IModifiable;

import java.util.List;

/**
 * Strategy for replacing vanilla fishing rods with TC equivalents.
 * Material selection delegates to shared single-tier helpers in TinkerToolBuilder.
 */
public final class FishingRodReplacementStrategy implements ReplacementStrategy {
    public static final FishingRodReplacementStrategy INSTANCE = new FishingRodReplacementStrategy();

    private FishingRodReplacementStrategy() {}

    @Override
    public List<IModifiable> findEligible(VanillaItemMappings.ReplacementInfo info) {
        VanillaItemMappings.FishingRodInfo rodInfo = (VanillaItemMappings.FishingRodInfo) info;
        return TcItemRegistry.getEligibleFishingRods(rodInfo.fishingRodType());
    }

    @Override
    public @Nullable List<MaterialVariantId> selectMaterials(
            VanillaItemMappings.ReplacementInfo info,
            List<MaterialStatsId> statTypes,
            RandomSource random) {
        VanillaItemMappings.FishingRodInfo rodInfo = (VanillaItemMappings.FishingRodInfo) info;
        return TinkerToolBuilder.selectMaterialsByCanonicals(rodInfo.canonicalMaterials(), statTypes, random);
    }
}
