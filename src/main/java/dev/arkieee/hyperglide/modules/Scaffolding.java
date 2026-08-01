package dev.arkieee.hyperglide.modules;

import dev.arkieee.hyperglide.Hyperglide;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import java.util.*;

public class Scaffolding extends Module {
    private static final double edge = 0.85;
    private static final double extend = 1.0;
    private static final double step = 0.25;
    private static final double reach = 2.0;
    private static final int life = 10;

    private final SettingGroup general = this.settings.getDefaultGroup();
    private final SettingGroup visuals = this.settings.createGroup("Visuals");

    private final Setting<List<Block>> blocks = this.general.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks used by Scaffolding.")
        .build()
    );

    private final Setting<Mode> mode = this.general.add(new EnumSetting.Builder<Mode>()
        .name("list-mode")
        .description("How the block list is used.")
        .defaultValue(Mode.Whitelist)
        .build()
    );

    private final Setting<Integer> delay = this.general.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Delay in ticks between placements.")
        .defaultValue(3)
        .min(1)
        .sliderMax(4)
        .build()
    );

    private final Setting<Boolean> dynamic = this.general.add(new BoolSetting.Builder()
        .name("dynamic-delay")
        .description("Uses a one-tick delay when close to an air block.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> render = this.visuals.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders queued placements.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shape = this.visuals.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape")
        .description("How queued placements are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> side = this.visuals.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The fill color of queued placements.")
        .defaultValue(new SettingColor(255, 255, 255, 32))
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> line = this.visuals.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The outline color of queued placements.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(this.render::get)
        .build()
    );

    private final LinkedHashSet<BlockPos> queue = new LinkedHashSet<>();
    private final Map<BlockPos, Integer> marks = new HashMap<>();
    private final BlockPos.Mutable scan = new BlockPos.Mutable();

    private int timer;
    private int tick;
    private int level;

    public enum Mode {
        Whitelist,
        Blacklist
    }

    public Scaffolding() {
        super(Hyperglide.CATEGORY, "scaffolding",
            "Places queued blocks under and ahead of the player."
        );
    }

    @Override
    public void onActivate() {
        this.reset();
        this.timer = this.delay.get();
        this.level = this.mc.player == null ? 0 : this.layer();
    }

    @Override
    public void onDeactivate() {
        this.reset();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (this.mc.player == null || this.mc.world == null
            || this.mc.interactionManager == null) return;

        this.tick++;
        this.update();
        this.clean();
        this.collect();

        if (++this.timer < this.pace()) return;

        BlockPos pos = this.next();
        if (pos == null) return;

        int slot = this.slot(pos);
        if (slot == -1) {
            this.add(pos, true);
            return;
        }

        if (!this.place(pos, slot)) return;

        this.timer = 0;
        this.marks.put(pos, this.tick);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!this.render.get() || this.mc.world == null) {
            return;
        }

        for (BlockPos pos : this.queue) {
            this.box(event, pos);
        }

        for (BlockPos pos : this.marks.keySet()) {
            if (!this.queue.contains(pos)) this.box(event, pos);
        }
    }

    private void reset() {
        this.queue.clear();
        this.marks.clear();

        this.timer = 0;
        this.tick = 0;
    }

    private void update() {
        int level = this.layer();
        if (this.level == level) return;

        this.level = level;
        this.queue.clear();
    }

    private void collect() {
        this.add(BlockPos.ofFloored(
            this.mc.player.getX(), this.level, this.mc.player.getZ()
        ), true);

        Vec3d move = this.move();
        if (move.lengthSquared() == 0) return;

        for (double idx = step; idx <= extend + 0.001; idx += step) {
            this.add(BlockPos.ofFloored(
                this.mc.player.getX() + move.x * idx, this.level,
                this.mc.player.getZ() + move.z * idx
            ), false);
        }
    }

    private void add(BlockPos pos, boolean first) {
        pos = pos.toImmutable();

        if (!this.open(pos) || this.queue.contains(pos)
            || this.marks.containsKey(pos)) return;

        if (first) this.queue.addFirst(pos);
        else this.queue.addLast(pos);
    }

    private BlockPos next() {
        while (!this.queue.isEmpty()) {
            BlockPos pos = this.queue.removeFirst();
            if (this.valid(pos)) return pos;
        }
        return null;
    }

    private void clean() {
        this.queue.removeIf(pos -> !this.valid(pos));

        this.marks.entrySet().removeIf(entry -> {
            BlockPos pos = entry.getKey();
            return !this.open(pos) || this.far(pos) ||
                this.tick - entry.getValue() > life;
        });
    }

    private boolean place(BlockPos pos, int slot) {
        ItemStack stack = this.mc.player.getInventory().getStack(slot);

        if (!(stack.getItem() instanceof BlockItem item)) return false;
        if (!InvUtils.swap(slot, true)) return false;

        try {
            this.mc.interactionManager.interactBlock(
                this.mc.player, Hand.MAIN_HAND, this.hit(pos)
            );

            this.mc.player.swingHand(Hand.MAIN_HAND);
            this.sound(item, pos);

            return true;
        } finally {
            InvUtils.swapBack();
        }
    }

    private BlockHitResult hit(BlockPos pos) {
        for (Direction side : Direction.values()) {
            BlockPos near = pos.offset(side);
            BlockState state = this.mc.world.getBlockState(near);

            if (state.isReplaceable() || !state.getFluidState().isEmpty()) {
                continue;
            }

            Direction face = side.getOpposite();
            Vec3d hit = Vec3d.ofCenter(near).add(
                face.getOffsetX() * 0.5,
                face.getOffsetY() * 0.5,
                face.getOffsetZ() * 0.5
            );

            return new BlockHitResult(hit, face, near, false);
        }

        return new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
    }

    private int slot(BlockPos pos) {
        for (int idx = 0; idx < 9; idx++) {
            ItemStack stack = this.mc.player.getInventory().getStack(idx);
            if (!(stack.getItem() instanceof BlockItem item)) continue;

            Block block = item.getBlock();
            if (!this.allowed(block) || block instanceof FallingBlock) continue;

            if (Block.isShapeFullCube(
                block.getDefaultState().getCollisionShape(this.mc.world, pos)
            )) return idx;
        }
        return -1;
    }

    private boolean allowed(Block block) {
        return this.mode.get() == Mode.Blacklist
            ? !this.blocks.get().contains(block)
            : this.blocks.get().contains(block);
    }

    private int pace() {
        return this.dynamic.get() && this.close() ? 1 : this.delay.get();
    }

    private boolean close() {
        int x = (int) Math.round(this.mc.player.getX());
        int z = (int) Math.round(this.mc.player.getZ());

        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                this.scan.set(x + ox, this.level, z + oz);

                if (!this.mc.world.getBlockState(this.scan).isAir()) continue;

                double dx = this.scan.getX() + 0.5 - this.mc.player.getX();
                double dz = this.scan.getZ() + 0.5 - this.mc.player.getZ();
                if (dx * dx + dz * dz < edge * edge) return true;
            }
        }

        return false;
    }

    private Vec3d move() {
        Vec3d move = Vec3d.ZERO;
        float yaw = this.mc.player.getYaw();

        if (this.mc.options.forwardKey.isPressed()) {
            move = move.add(Vec3d.fromPolar(0, yaw));
        }

        if (this.mc.options.backKey.isPressed()) {
            move = move.add(Vec3d.fromPolar(0, yaw + 180));
        }

        if (this.mc.options.leftKey.isPressed()) {
            move = move.add(Vec3d.fromPolar(0, yaw - 90));
        }

        if (this.mc.options.rightKey.isPressed()) {
            move = move.add(Vec3d.fromPolar(0, yaw + 90));
        }

        return move.lengthSquared() == 0 ? Vec3d.ZERO : move.normalize();
    }

    private int layer() {
        return BlockPos.ofFloored(
            this.mc.player.getX(), this.mc.player.getY(),
            this.mc.player.getZ()
        ).down().getY();
    }

    private boolean open(BlockPos pos) {
        return pos.getY() == this.level &&
            this.mc.world.getBlockState(pos).isReplaceable();
    }

    private boolean valid(BlockPos pos) {
        return this.open(pos) && !this.far(pos);
    }

    private boolean far(BlockPos pos) {
        double x = pos.getX() + 0.5 - this.mc.player.getX();
        double z = pos.getZ() + 0.5 - this.mc.player.getZ();
        return x * x + z * z > reach * reach;
    }

    private void box(Render3DEvent event, BlockPos pos) {
        event.renderer.box(pos, this.side.get(),
            this.line.get(), this.shape.get(), 0);
    }

    private void sound(BlockItem item, BlockPos pos) {
        BlockSoundGroup sound = item.getBlock().getDefaultState().getSoundGroup();

        this.mc.world.playSound(pos.getX() + 0.5, pos.getY() + 0.5,
            pos.getZ() + 0.5, sound.getPlaceSound(), SoundCategory.BLOCKS,
            (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F, false
        );
    }
}
