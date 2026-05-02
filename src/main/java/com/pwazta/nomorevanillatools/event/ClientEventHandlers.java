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
     * Line 2 (gray): clarifies parts matching requirement — varies by config.
     */
    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        CompoundTag tag = event.getItemStack().getTag();
        if (tag == null || !tag.contains("nmvt_required_tier")) return;

        String tier = tag.getString("nmvt_required_tier");
        String matchMode = tag.getString("nmvt_match_mode");
        boolean isArmor = "armor_slot".equals(matchMode);
        boolean isRanged = "ranged".equals(matchMode);
        boolean isShield = "shield".equals(matchMode);

        if (isArmor) {
            // Armor: plating tier requirement. Label is "3" (single) or "0-1" (range).
            if (tier.contains("-")) {
                String[] parts = tier.split("-", 2);
                event.getToolTip().add(Component.translatable(
                        "tooltip.nomorevanillatools.required_plating_tier_range", parts[0], parts[1])
                        .withStyle(ChatFormatting.GOLD));
            } else {
                event.getToolTip().add(Component.translatable(
                        "tooltip.nomorevanillatools.required_plating_tier", tier)
                        .withStyle(ChatFormatting.GOLD));
            }
        } else if (isRanged) {
            // Ranged: per-part tier details if mixed, otherwise single tier
            String partDetails = tag.getString("nmvt_part_details");
            if (!partDetails.isEmpty()) {
                event.getToolTip().add(Component.translatable(
                        "tooltip.nomorevanillatools.required_ranged_parts", partDetails)
                        .withStyle(ChatFormatting.GOLD));
            } else {
                event.getToolTip().add(Component.translatable(
                        "tooltip.nomorevanillatools.required_ranged", tier)
                        .withStyle(ChatFormatting.GOLD));
            }
        } else if (isShield) {
            // Shield: per-part tier details if mixed, otherwise single tier
            String partDetails = tag.getString("nmvt_part_details");
            if (!partDetails.isEmpty()) {
                event.getToolTip().add(Component.translatable(
                        "tooltip.nomorevanillatools.required_shield_parts", partDetails)
                        .withStyle(ChatFormatting.GOLD));
            } else {
                event.getToolTip().add(Component.translatable(
                        "tooltip.nomorevanillatools.required_shield", tier)
                        .withStyle(ChatFormatting.GOLD));
            }
        } else {
            // Tool: head requirement + all-parts match clarification
            event.getToolTip().add(Component.translatable(
                    "tooltip.nomorevanillatools.required_tier", tier)
                    .withStyle(ChatFormatting.GOLD));

            if (Config.requireAllPartsMatch) {
                int pct = (int) (Config.allPartsThreshold * 100);
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
