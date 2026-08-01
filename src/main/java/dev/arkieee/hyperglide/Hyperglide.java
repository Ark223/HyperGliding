package dev.arkieee.hyperglide;

import com.mojang.logging.LogUtils;
import dev.arkieee.hyperglide.modules.AirPlace;
import dev.arkieee.hyperglide.modules.AutoWeb;
import dev.arkieee.hyperglide.modules.BounceFly;
import dev.arkieee.hyperglide.modules.ControlFly;
import dev.arkieee.hyperglide.modules.DeepTrace;
import dev.arkieee.hyperglide.modules.EasyAccess;
import dev.arkieee.hyperglide.modules.FastPortal;
import dev.arkieee.hyperglide.modules.FD3Crafter;
import dev.arkieee.hyperglide.modules.MiningTweaks;
import dev.arkieee.hyperglide.modules.Overview;
import dev.arkieee.hyperglide.modules.Scaffolding;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class Hyperglide extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Hyperglide");
    public static final HudGroup HUD_GROUP = new HudGroup("Hyperglide");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Hyperglide");
        Modules.get().add(new AirPlace());
        Modules.get().add(new AutoWeb());
        Modules.get().add(new BounceFly());
        Modules.get().add(new ControlFly());
        Modules.get().add(new DeepTrace());
        Modules.get().add(new EasyAccess());
        Modules.get().add(new FastPortal());
        Modules.get().add(new FD3Crafter());
        Modules.get().add(new MiningTweaks());
        Modules.get().add(new Overview());
        Modules.get().add(new Scaffolding());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "dev.arkieee.hyperglide";
    }
}
