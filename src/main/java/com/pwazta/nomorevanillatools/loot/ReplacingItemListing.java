package com.pwazta.nomorevanillatools.loot;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import org.jetbrains.annotations.Nullable;
import slimeknights.tconstruct.library.materials.MaterialRegistry;

/**
 * Wraps an {@link VillagerTrades.ItemListing} and routes its produced {@link MerchantOffer}'s
 * result through {@link TinkerToolBuilder#tryReplace}. Cost stacks intentionally untouched —
 * see CONTEXT.md "Villager trade input limitation".
 *
 * <p>Round-trips through {@link CompoundTag} rather than constructor-copying because
 * {@link MerchantOffer}'s public constructors don't expose {@code rewardExp}.
 */
public record ReplacingItemListing(VillagerTrades.ItemListing delegate) implements VillagerTrades.ItemListing {

    @Override
    public @Nullable MerchantOffer getOffer(Entity trader, RandomSource random) {
        MerchantOffer offer = delegate.getOffer(trader, random);
        if (offer == null) return null;
        if (!MaterialRegistry.isFullyLoaded()) return offer;

        ItemStack newResult = TinkerToolBuilder.tryReplace(offer.getResult(), random);
        if (newResult == null) return offer;

        CompoundTag tag = offer.createTag();
        tag.put("sell", newResult.save(new CompoundTag()));
        return new MerchantOffer(tag);
    }
}
