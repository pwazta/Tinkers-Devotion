package com.pwazta.tinkersdevotion.loot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pwazta.tinkersdevotion.Config;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import org.jetbrains.annotations.NotNull;
import slimeknights.tconstruct.library.materials.MaterialRegistry;

/**
 * Global Loot Modifier that replaces vanilla tools, armor, and ranged weapons in chest/fishing loot
 * with randomized Tinker's Construct equivalents.
 *
 * Intercepts all loot generation (post loot table evaluation, including items
 * injected by other mods). Each vanilla tool/armor item is replaced in-place.
 */
public class VanillaLootReplacer extends LootModifier {

    public static final Codec<VanillaLootReplacer> CODEC = RecordCodecBuilder.create(inst -> codecStart(inst).apply(inst, VanillaLootReplacer::new));

    public VanillaLootReplacer(LootItemCondition[] conditions) {
        super(conditions);
    }

    @Override
    protected @NotNull ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        if (!Config.replaceLootTableItems) return generatedLoot;
        if (!MaterialRegistry.isFullyLoaded()) return generatedLoot;

        RandomSource random = context.getRandom();
        for (int i = 0; i < generatedLoot.size(); i++) {
            ItemStack original = generatedLoot.get(i);
            ItemStack replacement = TinkerToolBuilder.tryReplace(original, random);
            if (replacement != null) generatedLoot.set(i, replacement);
        }
        return generatedLoot;
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }
}
