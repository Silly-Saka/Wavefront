package com.redsmods.wavefront;

import com.redsmods.wavefront.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//? if fabric {
import dev.kikugie.fletching_table.annotation.fabric.Entrypoint;
import net.fabricmc.api.ModInitializer;
//?}
//? if neoforge {
//import net.neoforged.api.distmarker.Dist;
//import net.neoforged.fml.ModLoadingContext;
//import net.neoforged.fml.common.Mod;
//import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
//?}

//? if forge {
/*import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.common.Mod;
*///?}


//? if neoforge {
//@Mod(value = "@MODID@", dist = Dist.CLIENT)
//?} else if forge {
/*@Mod(value = "@MODID@")
*///?} else {
@Entrypoint
//?}
public class WavefrontMod /*? if fabric {*/ implements ModInitializer /*?}*/ {
    public static final String MOD_ID = "wavefront";
    public static final Logger DEBUG_LOGGER = LoggerFactory.getLogger(MOD_ID);

    //? if fabric {
    @Override
    public void onInitialize() {
        Config.CONFIG.load();
        Config.refreshEffective();
    }
    //?}

    //? if neoforge {
//    public WavefrontMod() {
//        Config.CONFIG.load();
//        ModLoadingContext.get().registerExtensionPoint(IConfigScreenFactory.class, () -> (client, parent) -> Config.configScreen(parent));
//    }
	//?}
    //? if forge {
    /*public WavefrontMod() {
        Config.CONFIG.load();
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class, () -> new ConfigScreenHandler.ConfigScreenFactory(
                (mc, parent) -> Config.CONFIG.generateGui().generateScreen(parent)
        ));
    }
    *///?}
}
