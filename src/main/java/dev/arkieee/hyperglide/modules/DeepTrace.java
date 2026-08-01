package dev.arkieee.hyperglide.modules;

import dev.arkieee.hyperglide.Hyperglide;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;

public class DeepTrace extends Module {
    private final SettingGroup general = this.settings.getDefaultGroup();
    private final SettingGroup render = this.settings.createGroup("Render");

    private final Setting<List<Item>> blacklist = this.general.add(
        new ItemListSetting.Builder()
            .name("blacklist")
            .description("Dropped items detected below the maximum Y level.")
            .defaultValue(
                Items.ALLIUM, Items.AZALEA, Items.AZURE_BLUET, Items.BLUE_ORCHID,
                Items.BROWN_MUSHROOM, Items.CORNFLOWER, Items.DANDELION,
                Items.FLOWERING_AZALEA, Items.GLOW_INK_SAC, Items.GRAVEL,
                Items.INK_SAC, Items.LILAC, Items.LILY_OF_THE_VALLEY,
                Items.MOSS_CARPET, Items.ORANGE_TULIP, Items.OXEYE_DAISY,
                Items.PEONY, Items.PINK_PETALS, Items.PINK_TULIP, Items.POPPY,
                Items.RAIL, Items.RED_MUSHROOM, Items.RED_SAND, Items.RED_TULIP,
                Items.ROSE_BUSH, Items.SAND, Items.SPORE_BLOSSOM, Items.STRING,
                Items.SUNFLOWER, Items.TORCH, Items.WHEAT_SEEDS, Items.WHITE_TULIP
            )
            .build()
    );

    private final Setting<Integer> level = this.general.add(
        new IntSetting.Builder()
            .name("max-level")
            .description("Maximum Y level where watched items are detected.")
            .defaultValue(64)
            .min(-64)
            .sliderMax(64)
            .build()
    );

    private final Setting<ShapeMode> shape = this.render.add(
        new EnumSetting.Builder<ShapeMode>()
            .name("shape")
            .description("How detected item boxes are rendered.")
            .defaultValue(ShapeMode.Both)
            .build()
    );

    private final Setting<SettingColor> side = this.render.add(
        new ColorSetting.Builder()
            .name("side-color")
            .description("The fill color of detected item boxes.")
            .defaultValue(new SettingColor(255, 80, 80, 32))
            .build()
    );

    private final Setting<SettingColor> line = this.render.add(
        new ColorSetting.Builder()
            .name("line-color")
            .description("The outline and tracer color.")
            .defaultValue(new SettingColor(255, 80, 80, 255))
            .build()
    );

    private final Setting<Boolean> tracers = this.render.add(
        new BoolSetting.Builder()
            .name("tracers")
            .description("Draws tracers to detected items.")
            .defaultValue(true)
            .build()
    );

    private int count;

    public DeepTrace() {
        super(Hyperglide.CATEGORY, "deep-trace",
            "Highlights unusual dropped items found deep underground."
        );
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        this.count = 0;

        if (this.mc.world == null ||
            this.mc.player == null) return;

        for (Entity entity : this.mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity item) ||
                !item.isAlive() ||
                item.getBlockY() >= this.level.get() - 1 ||
                !EntityUtils.isInRenderDistance(item)) {
                continue;
            }

            ItemStack stack = item.getStack();

            if (stack.isEmpty() ||
                this.blacklist.get().contains(stack.getItem())) {
                continue;
            }

            Vec3d pos = item.getLerpedPos(event.tickDelta);
            Box box = item.getBoundingBox().offset(
                pos.x - item.getX(),
                pos.y - item.getY(),
                pos.z - item.getZ()
            );

            event.renderer.box(
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                this.side.get(), this.line.get(), this.shape.get(), 0
            );

            if (this.tracers.get()) {
                Vec3d start = RenderUtils.center;
                Vec3d end = box.getCenter();

                event.renderer.line(
                    start.x, start.y, start.z, end.x, end.y, end.z,
                    this.line.get()
                );
            }

            this.count++;
        }
    }

    @Override
    public String getInfoString() {
        return Integer.toString(this.count);
    }
}
