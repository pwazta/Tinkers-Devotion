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
 * Strategy for replacing vanilla shields with TC equivalents.
 * Material selection delegates to shared single-tier helpers in TinkerToolBuilder.
 */
public final class ShieldReplacementStrategy implements ReplacementStrategy {
    public static final ShieldReplacementStrategy INSTANCE = new ShieldReplacementStrategy();

    private ShieldReplacementStrategy() {}

    @Override
    public List<IModifiable> findEligible(VanillaItemMappings.ReplacementInfo info) {
        VanillaItemMappings.ShieldInfo shieldInfo = (VanillaItemMappings.ShieldInfo) info;
        return TcItemRegistry.getEligibleShields(shieldInfo.shieldType());
    }

    @Override
    public @Nullable List<MaterialVariantId> selectMaterials(
            VanillaItemMappings.ReplacementInfo info,
            List<MaterialStatsId> statTypes,
            RandomSource random) {
        VanillaItemMappings.ShieldInfo shieldInfo = (VanillaItemMappings.ShieldInfo) info;
        return TinkerToolBuilder.selectMaterialsByCanonicals(shieldInfo.canonicalMaterials(), statTypes, random);
    }
}
