package com.pwazta.nomorevanillatools.loot.strategy;

import com.pwazta.nomorevanillatools.loot.TinkerToolBuilder;
import com.pwazta.nomorevanillatools.loot.VanillaItemMappings;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.materials.stats.MaterialStatsId;
import slimeknights.tconstruct.library.tools.item.IModifiable;

import java.util.List;

/**
 * Strategy for building a TC replacement of a vanilla item category.
 *
 * <p>Material-based strategies (tools, armor, ranged, shield, fishing rod) override
 * {@link #selectMaterials} only — the default {@link #buildReplacement} runs the shared
 * material pipeline for them.
 *
 * <p>Material-less strategies (TC tools defined without a {@code MaterialStatsModule},
 * e.g. flint and brick) override {@link #buildReplacement} directly and ignore the
 * default no-op {@link #selectMaterials}.
 */
public interface ReplacementStrategy {

    /** Eligible TC items for the given replacement info. */
    List<IModifiable> findEligible(VanillaItemMappings.ReplacementInfo info);

    /**
     * Per-part material selection. Default returns null for material-less strategies;
     * material-based strategies override.
     */
    default @Nullable List<MaterialVariantId> selectMaterials(
            VanillaItemMappings.ReplacementInfo info,
            List<MaterialStatsId> statTypes,
            RandomSource random) {
        return null;
    }

    /**
     * Builds the replacement ItemStack. Default delegates to
     * {@link TinkerToolBuilder#materialBasedBuild} which calls {@link #selectMaterials}
     * after resolving the tool's stat types. Override for material-less items.
     */
    default @Nullable ItemStack buildReplacement(
            VanillaItemMappings.ReplacementInfo info,
            IModifiable selected,
            ItemStack original,
            RandomSource random) {
        return TinkerToolBuilder.materialBasedBuild(this, info, selected, original, random);
    }
}
