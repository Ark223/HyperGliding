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
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import java.util.ArrayList;
import java.util.List;

public class FastPortal extends Module {
    private final SettingGroup general = this.settings.getDefaultGroup();
    private final SettingGroup visuals = this.settings.createGroup("Visuals");

    private final Setting<Integer> delay = this.general.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Delay in ticks between placements.")
        .defaultValue(2)
        .min(1)
        .sliderMax(5)
        .build()
    );

    private final Setting<Boolean> render = this.visuals.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders the remaining portal frame.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shape = this.visuals.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape")
        .description("How the portal frame is rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> side = this.visuals.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The portal frame fill color.")
        .defaultValue(new SettingColor(64, 0, 128, 32))
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> line = this.visuals.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The portal frame outline color.")
        .defaultValue(new SettingColor(64, 0, 128, 255))
        .visible(this.render::get)
        .build()
    );

    private final List<BlockPos> portal = new ArrayList<>();

    private int index;
    private int timer;

    public FastPortal() {
        super(Hyperglide.CATEGORY, "fast-portal",
            "Builds a portal in the direction you are looking."
        );
    }

    @Override
    public void onActivate() {
        if (this.mc.player == null || this.mc.world == null) {
            this.toggle();
            return;
        }

        this.portal.clear();
        this.index = 0;
        this.timer = this.delay.get();

        this.collect();

        int need = 0;

        for (BlockPos pos : this.portal) {
            if (this.mc.world.getBlockState(pos).isOf(Blocks.OBSIDIAN)) {
                continue;
            }

            if (!this.mc.world.getBlockState(pos).isReplaceable()) {
                this.error("Portal area is obstructed.");
                this.toggle();
                return;
            }

            need++;
        }

        if (need == 0) {
            this.info("A complete portal already exists here.");
            this.toggle();
            return;
        }

        if (this.count() < need) {
            this.error("Not enough obsidian in hotbar (need " + need + ").");
            this.toggle();
        }
    }

    @Override
    public void onDeactivate() {
        this.portal.clear();
        this.index = 0;
        this.timer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (this.mc.player == null || this.mc.world == null) {
            return;
        }

        this.skip();

        if (this.index >= this.portal.size()) {
            this.done();
            return;
        }

        if (++this.timer < this.delay.get()) return;

        BlockPos pos = this.portal.get(this.index);

        if (!this.mc.world.getBlockState(pos).isReplaceable()) {
            this.error("Portal area became obstructed.");
            this.toggle();
            return;
        }

        int slot = this.slot();

        if (slot == -1) {
            this.error("No obsidian found in the hotbar.");
            this.toggle();
            return;
        }

        if (!this.place(pos, slot)) return;

        this.index++;
        this.timer = 0;
        this.skip();

        if (this.index >= this.portal.size()) this.done();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!this.render.get()) return;

        for (int idx = this.index; idx < this.portal.size(); idx++) {
            BlockPos pos = this.portal.get(idx);

            if (!this.mc.world.getBlockState(pos).isOf(Blocks.OBSIDIAN)) {
                event.renderer.box(pos, this.side.get(),
                    this.line.get(), this.shape.get(), 0
                );
            }
        }
    }

    private void collect() {
        Direction front = this.mc.player.getHorizontalFacing();
        Direction right = front.rotateYClockwise();

        BlockPos base = BlockPos.ofFloored(
            this.mc.player.getX() + front.getOffsetX() * 2,
            this.mc.player.getY(),
            this.mc.player.getZ() + front.getOffsetZ() * 2
        ).offset(right, -1);

        this.portal.add(base.offset(right, 1));
        this.portal.add(base.offset(right, 2));

        for (int idx = 1; idx <= 3; idx++) {
            this.portal.add(base.up(idx));
        }

        for (int idx = 1; idx <= 3; idx++) {
            this.portal.add(base.offset(right, 3).up(idx));
        }

        this.portal.add(base.offset(right, 1).up(4));
        this.portal.add(base.offset(right, 2).up(4));
    }

    private void skip() {
        while (this.index < this.portal.size() &&
            this.mc.world.getBlockState(
                this.portal.get(this.index)
            ).isOf(Blocks.OBSIDIAN)
        ) {
            this.index++;
        }
    }

    private boolean place(BlockPos pos, int slot) {
        if (!InvUtils.swap(slot, true)) return false;

        PlayerActionC2SPacket swap = new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
            BlockPos.ORIGIN, Direction.DOWN
        );

        BlockHitResult hit = new BlockHitResult(
            Vec3d.ofCenter(pos), Direction.UP, pos, false
        );

        try {
            this.mc.player.networkHandler.sendPacket(swap);

            try {
                this.mc.player.networkHandler.sendPacket(
                    new PlayerInteractBlockC2SPacket(Hand.OFF_HAND, hit,
                        this.mc.player.currentScreenHandler.getRevision() + 2
                    )
                );
            } finally {
                this.mc.player.networkHandler.sendPacket(swap);
            }

            this.mc.player.swingHand(Hand.MAIN_HAND);
            this.sound(pos);

            return true;
        } finally {
            InvUtils.swapBack();
        }
    }

    private boolean ignite() {
        int slot = this.steel();
        if (slot == -1 || this.mc.interactionManager == null) {
            return false;
        }

        BlockPos pos = this.portal.get(0);

        BlockHitResult hit = new BlockHitResult(
            Vec3d.ofCenter(pos).add(0, 0.5, 0),
            Direction.UP, pos, false
        );

        if (!InvUtils.swap(slot, true)) {
            return false;
        }

        try {
            this.mc.interactionManager.interactBlock(
                this.mc.player, Hand.MAIN_HAND, hit
            );
            this.mc.player.swingHand(Hand.MAIN_HAND);
            return true;
        } finally {
            InvUtils.swapBack();
        }
    }

    private int slot() {
        for (int idx = 0; idx < 9; idx++) {
            if (this.mc.player.getInventory().getStack(idx).isOf(Items.OBSIDIAN)) {
                return idx;
            }
        }
        return -1;
    }

    private int steel() {
        for (int idx = 0; idx < 9; idx++) {
            if (this.mc.player.getInventory().getStack(idx).isOf(Items.FLINT_AND_STEEL)) {
                return idx;
            }
        }
        return -1;
    }

    private int count() {
        int count = 0;

        for (int idx = 0; idx < 9; idx++) {
            ItemStack stack = this.mc.player.getInventory().getStack(idx);
            if (stack.isOf(Items.OBSIDIAN)) count += stack.getCount();
        }

        return count;
    }

    private void sound(BlockPos pos) {
        BlockSoundGroup sound = Blocks.OBSIDIAN.getDefaultState().getSoundGroup();

        this.mc.world.playSound(pos.getX() + 0.5, pos.getY() + 0.5,
            pos.getZ() + 0.5, sound.getPlaceSound(), SoundCategory.BLOCKS,
            (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F, false
        );
    }

    private void done() {
        if (this.ignite()) this.info("Portal complete.");
        else this.error("Portal complete but unable to ignite.");

        this.toggle();
    }
}
