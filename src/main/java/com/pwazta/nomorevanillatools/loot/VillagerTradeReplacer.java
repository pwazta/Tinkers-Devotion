package com.pwazta.nomorevanillatools.loot;

import com.pwazta.nomorevanillatools.Config;
import com.pwazta.nomorevanillatools.ExampleMod;
import net.minecraft.world.entity.npc.VillagerTrades.ItemListing;
import net.minecraftforge.event.village.VillagerTradesEvent;
import net.minecraftforge.event.village.WandererTradesEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Wraps every {@link ItemListing} from {@link VillagerTradesEvent} and
 * {@link WandererTradesEvent} with a {@link ReplacingItemListing}.
 *
 * <p>Runs at {@link EventPriority#LOWEST} so addon handlers that <em>add</em> their
 * own modded listings (typical for trade-adder mods) have already populated the lists
 * by the time we wrap. Idempotency guard handles the rare case of a downstream
 * LOWEST-priority handler running after us and leaving us re-fired.
 */
@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class VillagerTradeReplacer {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onVillagerTrades(VillagerTradesEvent event) {
        if (!Config.replaceVillagerTrades) return;
        for (List<ItemListing> listings : event.getTrades().values()) wrapAll(listings);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onWandererTrades(WandererTradesEvent event) {
        if (!Config.replaceVillagerTrades) return;
        wrapAll(event.getGenericTrades());
        wrapAll(event.getRareTrades());
    }

    private static void wrapAll(List<ItemListing> listings) {
        listings.replaceAll(l -> l instanceof ReplacingItemListing ? l : new ReplacingItemListing(l));
    }
}
