package com.comicsquid.respawnanchor;

import com.comicsquid.respawnanchor.modules.AnchorDigger;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public final class DiggerRespawnAnchor extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Digger");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Digger Respawn Anchor");
        Modules.get().add(new AnchorDigger());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.comicsquid.respawnanchor";
    }
}
