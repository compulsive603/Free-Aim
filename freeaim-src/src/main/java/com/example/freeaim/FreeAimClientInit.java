package com.example.freeaim;

import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;

/** Client-only setup: gives the mod a "Config" button in the Mods list. */
final class FreeAimClientInit {
    private FreeAimClientInit() {}

    static void register() {
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new FreeAimConfigScreen(parent)));
    }
}
