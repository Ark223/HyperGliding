package dev.arkieee.hyperglide.modules;

import dev.arkieee.hyperglide.Hyperglide;
import dev.arkieee.hyperglide.mixin.InteractionAccessor;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import java.util.ArrayDeque;
import java.util.Deque;

public class MiningTweaks extends Module {
    private static final double threshold = 0.7;
    private static final long pause = 305;
    private static final int bursts = 22;
    private static final int height = 2048;

    private final SettingGroup general = this.settings.getDefaultGroup();
    private final SettingGroup visuals = this.settings.createGroup("Visuals");

    private final Setting<Boolean> remine = this.general.add(new BoolSetting.Builder()
        .name("instant-remine")
        .description("Automatically mines the last broken block when replaced.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> delay = this.general.add(new IntSetting.Builder()
        .name("verify-delay")
        .description("Ticks to wait before verifying whether a block was broken.")
        .defaultValue(5)
        .min(1)
        .sliderMax(10)
        .build()
    );

    private final Setting<Integer> retries = this.general.add(new IntSetting.Builder()
        .name("max-retries")
        .description("Maximum mining retries for each block.")
        .defaultValue(1)
        .min(0)
        .max(2)
        .sliderMax(2)
        .build()
    );

    private final Setting<Boolean> render = this.visuals.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders packet-mining progress and queued blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shape = this.visuals.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape")
        .description("How mining progress and queued blocks are rendered.")
        .defaultValue(ShapeMode.Lines)
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> qside = this.visuals.add(new ColorSetting.Builder()
        .name("queue-side-color")
        .description("The queued block fill color.")
        .defaultValue(new SettingColor(255, 255, 255, 32))
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> qline = this.visuals.add(new ColorSetting.Builder()
        .name("queue-line-color")
        .description("The queued block outline color.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> pside = this.visuals.add(new ColorSetting.Builder()
        .name("primary-side-color")
        .description("The primary block fill color.")
        .defaultValue(new SettingColor(255, 160, 0, 32))
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> pline = this.visuals.add(new ColorSetting.Builder()
        .name("primary-line-color")
        .description("The primary block outline color.")
        .defaultValue(new SettingColor(255, 160, 0, 255))
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> sside = this.visuals.add(new ColorSetting.Builder()
        .name("secondary-side-color")
        .description("The secondary block fill color.")
        .defaultValue(new SettingColor(255, 0, 0, 32))
        .visible(this.render::get)
        .build()
    );

    private final Setting<SettingColor> sline = this.visuals.add(new ColorSetting.Builder()
        .name("secondary-line-color")
        .description("The secondary block outline color.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .visible(this.render::get)
        .build()
    );

    private final Deque<Request> queue = new ArrayDeque<>();

    private Target primary;
    private Target secondary;
    private Request last;
    private int tick;

    public MiningTweaks() {
        super(Hyperglide.CATEGORY, "mining-tweaks",
            "Queues blocks for fast packet mining with double break."
        );
    }

    @Override
    public void onActivate() {
        this.reset();
    }

    @Override
    public void onDeactivate() {
        if (this.primary != null && !this.primary.finished) {
            this.action(this.primary,
                PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
                this.primary.pos, this.primary.side
            );
        }

        if (this.secondary != null && !this.secondary.finished) {
            this.action(this.secondary,
                PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
                this.secondary.pos, this.secondary.side
            );
        }

        this.reset();
    }

    public void mine(BlockPos pos, Direction side) {
        if (this.mc.player == null || this.mc.world == null ||
            this.mc.interactionManager == null || pos == null || side == null) {
            return;
        }

        pos = pos.toImmutable();
        if (this.tracked(pos)) return;

        BlockState state = this.mc.world.getBlockState(pos);
        if (!this.breakable(pos, state)) return;

        this.queue.addLast(new Request(pos, side, 0));
        this.fill();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (this.mc.player == null || this.mc.world == null
            || this.mc.interactionManager == null) return;

        this.tick++;

        this.clean();
        this.update(this.secondary);
        this.update(this.primary);
        this.fill();
        this.remine();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!this.render.get()) return;

        for (Request request : this.queue) {
            event.renderer.box(request.pos, this.qside.get(),
                this.qline.get(), this.shape.get(), 0
            );
        }

        if (this.secondary != null) {
            this.box(event, this.secondary,
                this.sside.get(), this.sline.get()
            );
        }

        if (this.primary != null) {
            this.box(event, this.primary,
                this.pside.get(), this.pline.get()
            );
        }
    }

    private void reset() {
        this.queue.clear();

        this.primary = null;
        this.secondary = null;
        this.last = null;
        this.tick = 0;
    }

    private void clean() {
        this.queue.removeIf(request -> {
            BlockState state = this.mc.world.getBlockState(request.pos);
            return !this.breakable(request.pos, state);
        });
    }

    private void fill() {
        if (this.primary == null) {
            Target target = this.next();
            if (target != null) this.begin(target);
        }

        if (this.primary == null || this.secondary != null ||
            this.queue.isEmpty() || !this.parkable()) return;

        Target target = this.next();
        if (target == null) return;

        this.park();
        this.begin(target);
    }

    private Target next() {
        while (!this.queue.isEmpty()) {
            Request request = this.queue.removeFirst();
            BlockState state = this.mc.world.getBlockState(request.pos);

            if (this.breakable(request.pos, state)) {
                return new Target(request, state);
            }
        }
        return null;
    }

    private boolean parkable() {
        return this.primary != null && !this.primary.finished &&
            !this.primary.instant && this.primary.progress < 1;
    }

    private void park() {
        Target target = this.primary;

        this.action(target,
            PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
            target.pos, target.side
        );

        target.primary = false;

        this.secondary = target;
        this.primary = null;
    }

    private void begin(Target target) {
        target.primary = true;
        target.started = System.currentTimeMillis();
        target.slot = this.best(target.state, target.pos);

        this.select(target.slot);

        target.delta = this.delta(target);
        target.instant = target.delta >= 1.0F;
        target.progress = target.instant ? 1 : 0;

        this.primary = target;

        this.packet(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
            target.pos, target.side
        );

        if (!target.instant) {
            this.packet(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
                this.fake(target.pos), target.side
            );
        }

        this.mc.player.swingHand(Hand.MAIN_HAND);
        if (target.instant) this.finish(target);
    }

    private void update(Target target) {
        if (target == null) return;

        BlockState state = this.mc.world.getBlockState(target.pos);

        if (state.isAir()) {
            this.confirm(target);
            return;
        }

        if (target.finished) {
            if (this.tick - target.finish >= this.delay.get()) {
                this.verify(target);
            }
            return;
        }

        target.slot = this.best(target.state, target.pos);
        target.delta = this.delta(target);
        target.progress = this.progress(target);

        long elapsed = System.currentTimeMillis() - target.started;

        if (!target.burst && elapsed >= pause &&
            this.duration(target) > pause && target.progress < 1) {
            this.burst(target);
        }

        if (target.progress >= 1) this.finish(target);
    }

    private void finish(Target target) {
        if (target.finished) return;

        if (!target.instant) {
            this.action(target,
                PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
                target.pos, target.side
            );
        }

        target.progress = 1;
        target.finished = true;
        target.finish = this.tick;
    }

    private void verify(Target target) {
        BlockState state = this.mc.world.getBlockState(target.pos);

        if (state.isAir()) {
            this.confirm(target);
            return;
        }

        this.remove(target);
        if (target.retry >= this.retries.get()) return;

        this.queue.addFirst(new Request(
            target.pos, target.side, target.retry + 1
        ));
    }

    private void confirm(Target target) {
        this.last = new Request(target.pos, target.side, 0);
        this.remove(target);
    }

    private void burst(Target target) {
        target.slot = this.best(target.state, target.pos);
        this.select(target.slot);

        BlockPos pos = this.fake(target.pos);

        for (int idx = 0; idx < bursts; idx++) {
            this.packet(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
                pos, target.side
            );
        }

        target.burst = true;
    }

    private double progress(Target target) {
        if (target.finished) return 1;
        if (target.delta <= 0) return 0;

        double diff = System.currentTimeMillis() - target.started;
        double ticks = Math.max(1.0, diff / 50.0);
        double limit = this.limit(target);

        return Math.min(1.0, target.delta * ticks / limit);
    }

    private long duration(Target target) {
        if (target.delta <= 0) return Long.MAX_VALUE;

        double limit = this.limit(target);
        return (long) Math.max(0, (limit / target.delta - 1.0) * 50.0);
    }

    private double limit(Target target) {
        if (!target.primary || target.retry > 0) return 1.0;
        return threshold;
    }

    private float delta(Target target) {
        PlayerInventory inv = this.mc.player.getInventory();
        int selected = inv.selectedSlot;

        inv.setSelectedSlot(target.slot);

        try {
            return target.state.calcBlockBreakingDelta(
                this.mc.player, this.mc.world, target.pos
            );
        } finally {
            inv.setSelectedSlot(selected);
        }
    }

    private int best(BlockState state, BlockPos pos) {
        PlayerInventory inv = this.mc.player.getInventory();
        int selected = inv.selectedSlot;
        int best = selected;

        float speed = -1;
        boolean suitable = false;
        boolean required = state.isToolRequired();

        try {
            for (int idx = 0; idx < 9; idx++) {
                ItemStack stack = inv.getStack(idx);
                boolean good = stack.isSuitableFor(state);

                inv.setSelectedSlot(idx);

                float value = state.calcBlockBreakingDelta(
                    this.mc.player, this.mc.world, pos
                );

                if (required && good != suitable) {
                    if (!good) continue;
                    best = idx;
                    speed = value;
                    suitable = true;
                    continue;
                }

                if (value <= speed) continue;

                best = idx;
                speed = value;
                suitable = good;
            }
        } finally {
            inv.setSelectedSlot(selected);
        }
        return best;
    }

    private void action(Target target, PlayerActionC2SPacket.Action
        action, BlockPos pos, Direction side) {
        target.slot = this.best(target.state, target.pos);

        this.select(target.slot);
        this.packet(action, pos, side);
    }

    private void select(int slot) {
        PlayerInventory inv = this.mc.player.getInventory();
        if (inv.selectedSlot == slot) return;

        inv.setSelectedSlot(slot);

        this.mc.player.networkHandler.sendPacket(
            new UpdateSelectedSlotC2SPacket(slot));
    }

    private void packet(PlayerActionC2SPacket.Action
        action, BlockPos pos, Direction side) {
        if (this.mc.world == null ||
            this.mc.interactionManager == null) return;

        ((InteractionAccessor) this.mc.interactionManager)
            .hyperglide$sendSequencedPacket(this.mc.world,
                sequence -> new PlayerActionC2SPacket(
                    action, pos, side, sequence
                )
            );
    }

    private BlockPos fake(BlockPos pos) {
        return new BlockPos(pos.getX(), height, pos.getZ());
    }

    private void remine() {
        if (!this.remine.get() || this.last == null ||
            this.primary != null || this.secondary != null ||
            !this.queue.isEmpty()) return;

        BlockState state = this.mc.world.getBlockState(this.last.pos);
        if (!this.breakable(this.last.pos, state)) return;

        this.queue.addLast(this.last);
        this.fill();
    }

    private void remove(Target target) {
        if (target == this.primary) this.primary = null;
        if (target == this.secondary) this.secondary = null;
    }

    private boolean tracked(BlockPos pos) {
        if (this.primary != null && this.primary.pos.equals(pos)) {
            return true;
        }
        if (this.secondary != null && this.secondary.pos.equals(pos)) {
            return true;
        }
        for (Request request : this.queue) {
            if (request.pos.equals(pos)) return true;
        }
        return false;
    }

    private boolean breakable(BlockPos pos, BlockState state) {
        return !state.isAir() && state.getHardness(this.mc.world, pos) >= 0;
    }

    private void box(Render3DEvent event, Target target,
        SettingColor side, SettingColor line) {
        double offset = (1.0 - target.progress) / 2.0;
        Box box = new Box(
            target.pos.getX() + offset,
            target.pos.getY() + offset,
            target.pos.getZ() + offset,
            target.pos.getX() + 1.0 - offset,
            target.pos.getY() + 1.0 - offset,
            target.pos.getZ() + 1.0 - offset
        );
        event.renderer.box(box, side, line, this.shape.get(), 0);
    }

    private record Request(BlockPos pos, Direction side, int retry) {
        private Request {
            pos = pos.toImmutable();
        }
    }

    private static class Target {
        private final BlockPos pos;
        private final BlockState state;
        private final Direction side;
        private final int retry;

        private long started;
        private float delta;
        private double progress;
        private int slot;

        private boolean primary;
        private boolean burst;
        private boolean instant;
        private boolean finished;
        private int finish;

        private Target(Request request, BlockState state) {
            this.pos = request.pos;
            this.state = state;
            this.side = request.side;
            this.retry = request.retry;
        }
    }
}
