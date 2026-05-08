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
     * Adds tooltip lines to JEI display stacks tagged with {@code nmvt_required_tier} or
     * {@code nmvt_match_mode}. Tier-bearing modes (tool/armor/ranged/shield/fishing_rod) show
     * the tier requirement; tierless modes (flint_and_steel) show a static line.
     */
    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        CompoundTag tag = event.getItemStack().getTag();
        if (tag == null) return;
        if (!tag.contains("nmvt_required_tier") && !tag.contains("nmvt_match_mode")) return;

        String tier = tag.getString("nmvt_required_tier");
        String matchMode = tag.getString("nmvt_match_mode");
        boolean isArmor = "armor_slot".equals(matchMode);
        boolean isRanged = "ranged".equals(matchMode);
        boolean isShield = "shield".equals(matchMode);
        boolean isFishingRod = "fishing_rod".equals(matchMode);
        boolean isFlintAndSteel = "flint_and_steel".equals(matchMode);

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
        } else if (isFishingRod) {
            // Fishing rod: per-part tier details if mixed, otherwise single tier
            String partDetails = tag.getString("nmvt_part_details");
            if (!partDetails.isEmpty()) {
                event.getToolTip().add(Component.translatable(
                        "tooltip.nomorevanillatools.required_fishing_rod_parts", partDetails)
                        .withStyle(ChatFormatting.GOLD));
            } else {
                event.getToolTip().add(Component.translatable(
                        "tooltip.nomorevanillatools.required_fishing_rod", tier)
                        .withStyle(ChatFormatting.GOLD));
            }
        } else if (isFlintAndSteel) {
            // Flint and steel: tierless static line — TC flint_and_brick has no material composition.
            event.getToolTip().add(Component.translatable(
                    "tooltip.nomorevanillatools.required_flint_and_steel")
                    .withStyle(ChatFormatting.GOLD));
        } else {
            // Tool: head requirement + all-parts match clarification
            event.getToolTip().add(Component.translatable(
                    "tooltip.nomorevanillatools.required_tier", tier)
                    .withStyle(ChatFormatting.GOLD));

            if (Config.partsMatchThreshold > 0.0) {
                int pct = (int) (Config.partsMatchThreshold * 100);
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
