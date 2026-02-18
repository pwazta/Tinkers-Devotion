package com.pwazta.nomorevanillatools.util;

import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableBowItem;
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableCrossbowItem;

/**
 * Shared predicates for identifying ranged weapons — both vanilla and TC variants.
 *
 * <p>Used by AI goals ({@code TcBowAttackGoal}, {@code TcCrossbowAttackGoal}) and client-side
 * animation mixins ({@code HumanoidModelMixin}, {@code SkeletonModelMixin}, etc.) to avoid
 * duplicating {@code instanceof} checks across the codebase.
 *
 * <p><b>Note:</b> {@link com.pwazta.nomorevanillatools.loot.ai.RangedGoalHelper RangedGoalHelper}
 * intentionally uses TC-only checks ({@code instanceof ModifiableBowItem} /
 * {@code ModifiableCrossbowItem}) and should <em>not</em> use these inclusive predicates.
 */
public final class TcRangedItems {

    private TcRangedItems() {}

    /** Returns true if the item is a vanilla bow or a TC bow. */
    public static boolean isBow(Item item) {
        return item instanceof BowItem || item instanceof ModifiableBowItem;
    }

    /** Returns true if the item is a vanilla crossbow or a TC crossbow. */
    public static boolean isCrossbow(Item item) {
        return item instanceof CrossbowItem || item instanceof ModifiableCrossbowItem;
    }

    /** Returns true if the item is any bow or crossbow (vanilla or TC). */
    public static boolean isRangedWeapon(Item item) {
        return isBow(item) || isCrossbow(item);
    }
}
