package com.pwazta.nomorevanillatools.loot.strategy;

import com.pwazta.nomorevanillatools.loot.VanillaItemMappings;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.materials.stats.MaterialStatsId;
import slimeknights.tconstruct.library.tools.item.IModifiable;

import java.util.List;

/**
 * Strategy for building a specific category of TC replacement items (tools, armor, ranged).
 * Each implementation encapsulates the eligible-item lookup and material selection logic
 * for its category, while the shared pipeline (stat types, build, enchant, damage transfer)
 * stays in {@link com.pwazta.nomorevanillatools.loot.TinkerToolBuilder TinkerToolBuilder}.
 */
public interface ReplacementStrategy {

    /** Finds all eligible TC items for the given replacement info. */
    List<IModifiable> findEligible(VanillaItemMappings.ReplacementInfo info);

    /**
     * Selects materials for each part slot of the replacement item.
     *
     * @param info      the replacement info (cast to the concrete record type by the implementation)
     * @param statTypes the stat types for each part slot of the selected TC item
     * @param random    random source for weighted selection
     * @return ordered list of materials matching statTypes, or null if selection fails
     */
    @Nullable List<MaterialVariantId> selectMaterials(
        VanillaItemMappings.ReplacementInfo info,
        List<MaterialStatsId> statTypes,
        RandomSource random);
}
