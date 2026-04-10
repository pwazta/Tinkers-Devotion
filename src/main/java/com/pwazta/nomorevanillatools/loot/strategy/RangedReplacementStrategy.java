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
 * Strategy for replacing vanilla ranged weapons (bow, crossbow) with TC equivalents.
 * Material selection delegates to shared single-tier helpers in TinkerToolBuilder.
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
        return TinkerToolBuilder.selectMaterialsByCanonicals(rangedInfo.canonicalMaterials(), statTypes, random);
    }
}
