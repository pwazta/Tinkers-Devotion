package com.pwazta.nomorevanillatools.loot.strategy;

import com.pwazta.nomorevanillatools.config.TiersToTcMaterials;
import com.pwazta.nomorevanillatools.loot.TinkerToolBuilder;
import com.pwazta.nomorevanillatools.loot.VanillaItemMappings;
import com.pwazta.nomorevanillatools.util.TcItemRegistry;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ArmorItem;
import org.jetbrains.annotations.Nullable;
import slimeknights.tconstruct.library.materials.MaterialRegistry;
import slimeknights.tconstruct.library.materials.definition.IMaterial;
import slimeknights.tconstruct.library.materials.definition.MaterialVariantId;
import slimeknights.tconstruct.library.materials.stats.MaterialStatsId;
import slimeknights.tconstruct.library.tools.item.IModifiable;

import java.util.ArrayList;
import java.util.List;

/**
 * Strategy for replacing vanilla armor pieces with TC armor.
 */
public final class ArmorReplacementStrategy implements ReplacementStrategy {

    public static final ArmorReplacementStrategy INSTANCE = new ArmorReplacementStrategy();

    /** Maximum IMaterial.getTier() allowed for armor inner parts. Caps at diamond-level. */
    private static final int ARMOR_INNER_MAX_TIER = 3;

    private ArmorReplacementStrategy() {}

    @Override
    public List<IModifiable> findEligible(VanillaItemMappings.ReplacementInfo info) {
        VanillaItemMappings.ArmorInfo armorInfo = (VanillaItemMappings.ArmorInfo) info;
        ArmorItem.Type armorType = VanillaItemMappings.getArmorType(armorInfo.slot());
        if (armorType == null) return List.of();
        return TcItemRegistry.getEligibleArmor(armorType, armorInfo.sets(), armorInfo.slot());
    }

    @Override
    public @Nullable List<MaterialVariantId> selectMaterials(VanillaItemMappings.ReplacementInfo info, List<MaterialStatsId> statTypes, RandomSource random) {
        VanillaItemMappings.ArmorInfo armorInfo = (VanillaItemMappings.ArmorInfo) info;
        List<MaterialVariantId> materials = new ArrayList<>();

        MaterialVariantId platingMaterial = selectPlatingMaterial(armorInfo.minTier(), armorInfo.maxTier(), random);
        if (platingMaterial == null) return null;
        materials.add(platingMaterial);

        IMaterial platingMat = MaterialRegistry.getInstance().getMaterial(platingMaterial.getId());
        int platingTcTier = platingMat.getTier();
        MaterialStatsId platingStat = statTypes.get(0);

        for (int i = 1; i < statTypes.size(); i++) {
            // Inner parts that share the plating stat type (e.g. laminar's second plating slot) reuse
            // the plating material to keep the visible armor surfaces consistent.
            MaterialVariantId partMaterial = statTypes.get(i).equals(platingStat)
                ? platingMaterial
                : selectArmorInnerMaterial(statTypes.get(i), platingTcTier, random);
            if (partMaterial == null) return null;
            materials.add(partMaterial);
        }
        return materials;
    }

    /**
     * Selects plating material for armor using IMaterial.getTier() range filtering.
     * 80% canonical, 20% random from tier-filtered pool.
     */
    private static @Nullable MaterialVariantId selectPlatingMaterial(int minTier, int maxTier, RandomSource random) {
        List<IMaterial> pool = TiersToTcMaterials.getPlatingMaterialsInTierRange(minTier, maxTier);
        if (pool.isEmpty()) return null;

        if (random.nextFloat() < TinkerToolBuilder.CANONICAL_WEIGHT) {
            String canonicalId = TiersToTcMaterials.getCanonicalArmorMaterial(minTier, maxTier);
            if (canonicalId != null) {
                MaterialVariantId canonicalVariant = MaterialVariantId.tryParse(canonicalId);
                if (canonicalVariant != null) {
                    boolean inPool = pool.stream()
                        .anyMatch(mat -> mat.getIdentifier().equals(canonicalVariant.getId()));
                    if (inPool) return canonicalVariant;
                }
            }
            // canonical missing/invalid — fall through to random
        }

        IMaterial selected = pool.get(random.nextInt(pool.size()));
        return MaterialVariantId.create(selected.getIdentifier(), "");
    }

    /**
     * Selects material for armor inner part (maille, shield_core, etc.).
     * Uniform random from compatible materials, filtered to plating tier or lower,
     * hard-capped at ARMOR_INNER_MAX_TIER.
     */
    private static @Nullable MaterialVariantId selectArmorInnerMaterial(MaterialStatsId statType, int platingTcTier, RandomSource random) {
        List<IMaterial> compatible = TinkerToolBuilder.getCompatibleMaterials(statType);
        if (compatible.isEmpty()) return null;

        int maxTier = Math.min(platingTcTier, ARMOR_INNER_MAX_TIER);
        List<IMaterial> filtered = compatible.stream()
            .filter(mat -> mat.getTier() <= maxTier)
            .toList();

        List<IMaterial> pool = filtered.isEmpty() ? compatible : filtered;
        IMaterial selected = pool.get(random.nextInt(pool.size()));
        return MaterialVariantId.create(selected.getIdentifier(), "");
    }
}
