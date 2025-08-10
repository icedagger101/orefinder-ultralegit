package com.koolname.bigcaves;

import com.koolname.bigcaves.modules.BigCavesFinder;
import com.koolname.bigcaves.modules.OreEsp;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BigCavesAddon extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger(BigCavesAddon.class);
    public static final Category CATEGORY = new Category("Cave Tools");
    public static final HudGroup HUD_GROUP = new HudGroup("Cave Tools");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Big Caves Addon");

        // Register custom modules under the Cave Tools category
        Modules.get().add(new BigCavesFinder());
        Modules.get().add(new OreEsp());
    }

    @Override
    public void onRegisterCategories() {
        // Register the custom category with the module system
        Modules.get().registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        // Return the base package name for this addon
        return "com.koolname.bigcaves";
    }
}
