package com.example.freeaim;

import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(FreeAimMod.MODID)
public class FreeAimMod {
    public static final String MODID = "freeaim";

    public FreeAimMod() {
        FreeAimNetwork.init();
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, FreeAimConfig.SPEC);
        if (FMLEnvironment.dist.isClient()) {
            FreeAimClientInit.register();   // separate class so servers never load client code
        }
    }
}
