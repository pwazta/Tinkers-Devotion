package com.pwazta.nomorevanillatools;

import com.mojang.logging.LogUtils;
import com.pwazta.nomorevanillatools.compat.jei.CraftingContainerConfigLoader;
import com.pwazta.nomorevanillatools.compat.jei.CraftingContainerRegistry;
import com.pwazta.nomorevanillatools.config.MaterialMappingConfig;
import com.pwazta.nomorevanillatools.config.ToolExclusionConfig;
import com.pwazta.nomorevanillatools.loot.ModGlobalLootModifiers;
import com.pwazta.nomorevanillatools.network.ModNetwork;
import net.minecraftforge.fml.loading.FMLPaths;
import com.pwazta.nomorevanillatools.recipe.ModRecipeSerializers;
import net.minecraft.client.Minecraft;
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
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(ExampleMod.MODID)
public class ExampleMod {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "nomorevanillatools";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();

    public ExampleMod(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register Global Loot Modifier codecs on the mod event bus
        ModGlobalLootModifiers.GLM.register(modEventBus);

        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);

        // Register our mod's ForgeConfigSpec so that Forge can create and load the config file for us
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // Initialize material mappings early so both client (JEI display) and server (crafting) have them
        MaterialMappingConfig.initialize(FMLPaths.CONFIGDIR.get().toFile());
        MaterialMappingConfig.initializeArmor(FMLPaths.CONFIGDIR.get().toFile());

        // Initialize tool/armor exclusion config (no registry dependency — static defaults)
        ToolExclusionConfig.initialize(FMLPaths.CONFIGDIR.get().toFile());

        // Register network packets
        ModNetwork.register();

        // Register crafting container configs for JEI transfer handlers
        CraftingContainerRegistry.registerBuiltIns();

        // Load extra container configs from user config file
        CraftingContainerConfigLoader.loadExtrasFromConfig(FMLPaths.CONFIGDIR.get());

        // Register custom ingredient serializers
        event.enqueueWork(() -> {
            ModRecipeSerializers.register();
            LOGGER.info("No More Vanilla Tools: Registered custom ingredient serializers");
        });
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Server starting event
        LOGGER.info("No More Vanilla Tools: Server starting");
    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            // Some client setup code
            LOGGER.info("HELLO FROM CLIENT SETUP");
            LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
        }
    }
}
