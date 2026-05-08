package com.pwazta.tinkersdevotion;

import com.pwazta.tinkersdevotion.compat.jei.CraftingContainerConfigLoader;
import com.pwazta.tinkersdevotion.compat.jei.CraftingContainerRegistry;
import com.pwazta.tinkersdevotion.config.ModifierSkipListConfig;
import com.pwazta.tinkersdevotion.config.ToolExclusionConfig;
import com.pwazta.tinkersdevotion.loot.ModGlobalLootModifiers;
import com.pwazta.tinkersdevotion.network.ModNetwork;
import net.minecraftforge.fml.loading.FMLPaths;
import com.pwazta.tinkersdevotion.recipe.ModRecipeSerializers;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(TinkersDevotion.MODID)
public class TinkersDevotion {
    public static final String MODID = "tinkersdevotion";

    public TinkersDevotion(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        modEventBus.addListener(this::commonSetup);
        ModGlobalLootModifiers.GLM.register(modEventBus);
        MinecraftForge.EVENT_BUS.register(this);
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        ToolExclusionConfig.initialize(FMLPaths.CONFIGDIR.get().toFile());
        ModifierSkipListConfig.initialize(FMLPaths.CONFIGDIR.get().toFile());
        ModNetwork.register();
        CraftingContainerRegistry.registerBuiltIns();
        CraftingContainerConfigLoader.loadExtrasFromConfig(FMLPaths.CONFIGDIR.get());

        event.enqueueWork(ModRecipeSerializers::register);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {}

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {}
    }
}
