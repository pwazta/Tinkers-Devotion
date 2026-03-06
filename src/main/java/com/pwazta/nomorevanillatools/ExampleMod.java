package com.pwazta.nomorevanillatools;

import com.pwazta.nomorevanillatools.compat.jei.CraftingContainerConfigLoader;
import com.pwazta.nomorevanillatools.compat.jei.CraftingContainerRegistry;
import com.pwazta.nomorevanillatools.config.MaterialMappingConfig;
import com.pwazta.nomorevanillatools.config.ModifierSkipListConfig;
import com.pwazta.nomorevanillatools.config.ModifierWeightsConfig;
import com.pwazta.nomorevanillatools.config.ToolExclusionConfig;
import com.pwazta.nomorevanillatools.loot.ModGlobalLootModifiers;
import com.pwazta.nomorevanillatools.network.ModNetwork;
import net.minecraftforge.fml.loading.FMLPaths;
import com.pwazta.nomorevanillatools.recipe.ModRecipeSerializers;
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

@Mod(ExampleMod.MODID)
public class ExampleMod {
    public static final String MODID = "nomorevanillatools";

    public ExampleMod(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        modEventBus.addListener(this::commonSetup);
        ModGlobalLootModifiers.GLM.register(modEventBus);
        MinecraftForge.EVENT_BUS.register(this);
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        MaterialMappingConfig.initialize(FMLPaths.CONFIGDIR.get().toFile());
        ToolExclusionConfig.initialize(FMLPaths.CONFIGDIR.get().toFile());
        ModifierSkipListConfig.initialize(FMLPaths.CONFIGDIR.get().toFile());
        ModifierWeightsConfig.initialize(FMLPaths.CONFIGDIR.get().toFile());
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
