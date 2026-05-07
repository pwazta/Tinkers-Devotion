package com.pwazta.nomorevanillatools.loot.strategy;

import com.pwazta.nomorevanillatools.config.TiersToTcMaterials;
import com.pwazta.nomorevanillatools.loot.TinkerToolBuilder;
import com.pwazta.nomorevanillatools.loot.VanillaItemMappings;
import com.pwazta.nomorevanillatools.util.TcItemRegistry;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;
import slimeknights.tconstruct.library.materials.MaterialRegistry;
import slimeknights.tconstruct.library.materials.definition.IMaterial;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.materials.stats.MaterialStatsId;
import slimeknights.tconstruct.library.tools.item.IModifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Strategy for replacing vanilla tools (swords, pickaxes, axes, shovels, hoes)
 * with TC melee/harvest tools.
 */
public final class ToolReplacementStrategy implements ReplacementStrategy {

    public static final ToolReplacementStrategy INSTANCE = new ToolReplacementStrategy();

    /** Probability threshold for selecting the basic (lowest tier) material for non-head parts. */
    private static final float OTHER_BASIC_WEIGHT = 0.50f;

    /** Cumulative threshold for basic + same-as-head selection for non-head parts. */
    private static final float OTHER_SAME_AS_HEAD_WEIGHT = 0.80f;

    private ToolReplacementStrategy() {}

    @Override
    public List<IModifiable> findEligible(VanillaItemMappings.ReplacementInfo info) {
        VanillaItemMappings.ToolInfo toolInfo = (VanillaItemMappings.ToolInfo) info;
        return TcItemRegistry.getEligibleTools(toolInfo.toolType());
    }

    @Override
    public @Nullable List<MaterialVariantId> selectMaterials(
            VanillaItemMappings.ReplacementInfo info,
            List<MaterialStatsId> statTypes,
            RandomSource random) {
        VanillaItemMappings.ToolInfo toolInfo = (VanillaItemMappings.ToolInfo) info;
        List<MaterialVariantId> materials = new ArrayList<>();

        MaterialVariantId headMaterial = selectHeadMaterial(toolInfo.tier(), random);
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

    /**
     * Selects head material for tools using weighted algorithm:
     * 80% canonical, 20% random from the live tier pool.
     */
    private static @Nullable MaterialVariantId selectHeadMaterial(String tier, RandomSource random) {
        Set<String> materials = TiersToTcMaterials.getToolMaterialsForTier(tier);
        if (materials.isEmpty()) return null;

        String selectedId;
        if (random.nextFloat() < TinkerToolBuilder.CANONICAL_WEIGHT) {
            selectedId = TiersToTcMaterials.getCanonicalToolMaterial(tier);
        } else {
            selectedId = materials.stream().skip(random.nextInt(materials.size())).findFirst().orElse(null);
        }
        if (selectedId == null) return null;

        return MaterialVariantId.tryParse(selectedId);
    }

    /**
     * Selects material for a non-head tool part using weighted algorithm:
     * 50% basic (lowest tier), 30% same as head, 20% random (same tier or lower).
     */
    private static @Nullable MaterialVariantId selectOtherPartMaterial(MaterialStatsId statType,
            int headTcTier, MaterialVariantId headMaterial, RandomSource random) {
        List<IMaterial> compatible = TinkerToolBuilder.getCompatibleMaterials(statType);
        if (compatible.isEmpty()) return null;

        List<IMaterial> filtered = compatible.stream()
            .filter(mat -> mat.getTier() <= headTcTier)
            .toList();

        List<IMaterial> pool = filtered.isEmpty() ? compatible : filtered;

        IMaterial basic = pool.get(0);
        for (IMaterial mat : pool) {
            if (mat.getTier() < basic.getTier()) basic = mat;
        }

        boolean headCompatible = compatible.stream()
            .anyMatch(mat -> mat.getIdentifier().equals(headMaterial.getId()));

        float roll = random.nextFloat();
        if (roll < OTHER_BASIC_WEIGHT) {
            return MaterialVariantId.create(basic.getIdentifier(), "");
        } else if (roll < OTHER_SAME_AS_HEAD_WEIGHT) {
            if (headCompatible) return headMaterial;
            return MaterialVariantId.create(basic.getIdentifier(), "");
        } else {
            IMaterial selected = pool.get(random.nextInt(pool.size()));
            return MaterialVariantId.create(selected.getIdentifier(), "");
        }
    }
}
