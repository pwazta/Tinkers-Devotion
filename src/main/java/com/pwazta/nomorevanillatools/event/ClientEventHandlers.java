package com.pwazta.nomorevanillatools.event;

import com.pwazta.nomorevanillatools.Config;
import com.pwazta.nomorevanillatools.ExampleMod;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-only event handlers. Registered via @EventBusSubscriber(Dist.CLIENT)
 * so these handlers are never loaded on a dedicated server.
 */
@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientEventHandlers {

    /**
     * Adds tooltip lines to JEI display stacks tagged with nmvt_required_tier.
     * Line 1 (gold): the head tier requirement.
     * Line 2 (gray): clarifies what other parts need to be — varies by config.
     */
    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        CompoundTag tag = event.getItemStack().getTag();
        if (tag == null || !tag.contains("nmvt_required_tier")) return;

        String tier = tag.getString("nmvt_required_tier");
        boolean isArmor = "armor_slot".equals(tag.getString("nmvt_match_mode"));
        boolean isRanged = "ranged".equals(tag.getString("nmvt_match_mode"));

        if (isArmor) {
            // Armor: plating requirement only (no "other parts" — armor skips that config)
            event.getToolTip().add(Component.translatable(
                    "tooltip.nomorevanillatools.required_plating_tier", tier)
                    .withStyle(ChatFormatting.GOLD));
        } else if (isRanged) {
            // Ranged: tier floor requirement
            event.getToolTip().add(Component.translatable(
                    "tooltip.nomorevanillatools.required_ranged", tier)
                    .withStyle(ChatFormatting.GOLD));
        } else {
            // Tool: head requirement + other parts clarification
            event.getToolTip().add(Component.translatable(
                    "tooltip.nomorevanillatools.required_tier", tier)
                    .withStyle(ChatFormatting.GOLD));

            if (Config.requireOtherPartsMatch) {
                int pct = (int) (Config.otherPartsThreshold * 100);
                if (pct >= 100) {
                    event.getToolTip().add(Component.translatable(
                            "tooltip.nomorevanillatools.all_parts", tier)
                            .withStyle(ChatFormatting.GRAY));
                } else {
                    event.getToolTip().add(Component.translatable(
                            "tooltip.nomorevanillatools.pct_other_parts", pct, tier)
                            .withStyle(ChatFormatting.GRAY));
                }
            } else {
                event.getToolTip().add(Component.translatable(
                        "tooltip.nomorevanillatools.any_other_parts")
                        .withStyle(ChatFormatting.GRAY));
            }
        }
    }
}
