package com.pwazta.tinkersdevotion.loot.strategy;

import com.pwazta.tinkersdevotion.loot.EnchantmentConverter;
import com.pwazta.tinkersdevotion.loot.TinkerToolBuilder;
import com.pwazta.tinkersdevotion.loot.VanillaItemMappings;
import com.pwazta.tinkersdevotion.util.TcItemRegistry;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import slimeknights.tconstruct.library.tools.definition.ToolDefinition;
import slimeknights.tconstruct.library.tools.item.IModifiable;
import slimeknights.tconstruct.library.tools.nbt.MaterialNBT;
import slimeknights.tconstruct.library.tools.nbt.ToolStack;

import java.util.List;

/**
 * Strategy for replacing vanilla flint and steel with TC flint and brick.
 *
 * <p>TC {@code flint_and_brick} has no {@code MaterialStatsModule} — its stats come from
 * {@code SetStatsModule} (fixed durability) and built-in trait modifiers (firestarter,
 * fiery, scorching) declared in the tool definition. There are no parts to select
 * materials for, so this strategy overrides {@link #buildReplacement} to skip the
 * material pipeline and build directly from {@link MaterialNBT#EMPTY}.
 */
public final class FlintAndSteelReplacementStrategy implements ReplacementStrategy {

    public static final FlintAndSteelReplacementStrategy INSTANCE = new FlintAndSteelReplacementStrategy();

    private FlintAndSteelReplacementStrategy() {}

    @Override
    public List<IModifiable> findEligible(VanillaItemMappings.ReplacementInfo info) {
        VanillaItemMappings.FlintAndSteelInfo flintInfo = (VanillaItemMappings.FlintAndSteelInfo) info;
        return TcItemRegistry.getEligibleFlintAndSteel(flintInfo.flintAndSteelType(), flintInfo.tcItemIds());
    }

    @Override
    public @Nullable ItemStack buildReplacement(
            VanillaItemMappings.ReplacementInfo info,
            IModifiable selected,
            ItemStack original,
            RandomSource random) {
        ToolDefinition definition = selected.getToolDefinition();
        if (!definition.isDataLoaded()) return null;

        ToolStack toolStack = ToolStack.createTool((Item) selected, definition, MaterialNBT.EMPTY);
        toolStack.rebuildStats();
        EnchantmentConverter.applyModifiers(toolStack, original, random);

        ItemStack result = toolStack.createStack();
        TinkerToolBuilder.transferDamage(original, result);
        return result;
    }
}
