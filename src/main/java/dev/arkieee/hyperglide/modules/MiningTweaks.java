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
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

public class MiningTweaks extends Module {
    private static final double threshold = 0.7;
    private static final long restart = 300;
    private static final long pause = 275;
    private static final int bursts = 22;
    private static final int height = 2048;

    private final SettingGroup general = this.settings.getDefaultGroup();
    private final SettingGroup visuals = this.settings.createGroup("Visuals");

    private final Setting<Boolean> remine = this.general.add(new BoolSetting.Builder()
        .name("instant-remine")
        .description("Instantly mines the last broken block when replaced.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> retries = this.general.add(new IntSetting.Builder()
        .name("maximum-retries")
        .description("Maximum mining retries after a block fails to break.")
        .defaultValue(1)
        .min(0)
        .sliderMax(2)
        .build()
    );

    private final Setting<Integer> cooldown = this.general.add(new IntSetting.Builder()
        .name("retry-cooldown")
        .description("Delay in ticks before starting another mining attempt.")
        .defaultValue(6)
        .min(1)
        .sliderMax(12)
        .build()
    );

    private final Setting<Integer> arming = this.general.add(new IntSetting.Builder()
        .name("tool-sync-delay")
        .description("Delay in ticks after switching tools before mining starts.")
        .defaultValue(3)
        .min(1)
        .sliderMax(5)
        .build()
    );

    private final Setting<Integer> valid = this.general.add(new IntSetting.Builder()
        .name("validation-wait")
        .description("Checks whether the block was mined after this many ticks.")
        .defaultValue(5)
        .min(1)
        .sliderMax(10)
        .build()
    );

    private final Setting<Integer> vanilla = this.general.add(new IntSetting.Builder()
        .name("vanilla-cutoff")
        .description("Uses vanilla mining for breaks within this limit in ticks.")
        .defaultValue(1)
        .min(0)
        .sliderMax(5)
        .build()
    );

    private final Setting<Boolean> render = this.visuals.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders packet mining progress and queued blocks.")
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
    private final Deque<Retry> waiting = new ArrayDeque<>();

    private Target primary;
    private Target secondary;
    private Request last;

    private int tick;

    private long stopped;
    private boolean fast;
    private long ready;
    private boolean paired;

    public MiningTweaks() {
        super(Hyperglide.CATEGORY, "mining-tweaks",
            "Queues blocks for reliable packet mining with double break."
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
                this.primary.pos
            );
        }

        if (this.secondary != null && !this.secondary.finished) {
            this.action(this.secondary,
                PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
                this.secondary.pos
            );
        }

        this.reset();
    }

    public boolean bypass(BlockPos pos) {
        if (this.mc.player == null ||
            this.mc.world == null || pos == null ||
            this.vanilla.get() <= 0 || this.tracked(pos)) {
            return false;
        }

        BlockState state = this.mc.world.getBlockState(pos);
        if (!this.breakable(pos, state)) return false;

        float delta = state.calcBlockBreakingDelta(
            this.mc.player, this.mc.world, pos
        );

        return delta >= 1.0F / this.vanilla.get();
    }

    public boolean mine(BlockPos pos, Direction side) {
        if (this.mc.player == null
            || this.mc.world == null
            || this.mc.interactionManager == null
            || pos == null || side == null) {
            return false;
        }

        pos = pos.toImmutable();
        if (this.tracked(pos)) return true;

        BlockState state = this.mc.world.getBlockState(pos);
        if (!this.breakable(pos, state)) return false;

        this.queue.addLast(new Request(pos, side, 0));

        this.fill();
        return true;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (this.mc.player == null
            || this.mc.world == null
            || this.mc.interactionManager == null) {
            return;
        }

        this.tick++;

        this.promote();
        this.clean();
        this.remine();
        this.fill();

        this.update(this.secondary);
        this.update(this.primary);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!this.render.get()) return;

        for (Request request : this.queue) {
            event.renderer.box(
                request.pos, this.qside.get(),
                this.qline.get(), this.shape.get(), 0
            );
        }

        for (Retry retry : this.waiting) {
            event.renderer.box(
                retry.request.pos, this.qside.get(),
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
        this.waiting.clear();

        this.primary = null;
        this.secondary = null;
        this.last = null;

        this.tick = 0;

        this.stopped = 0;
        this.fast = false;
        this.ready = 0;
        this.paired = false;
    }

    private void promote() {
        if (this.waiting.isEmpty()) return;

        long now = System.currentTimeMillis();
        Iterator<Retry> iterator = this.waiting.iterator();

        while (iterator.hasNext()) {
            Retry retry = iterator.next();
            if (now < retry.ready) continue;

            BlockState state = this.mc.world.getBlockState(retry.request.pos);
            iterator.remove();

            if (!this.breakable(retry.request.pos, state)) continue;
            this.queue.addFirst(retry.request);
        }
    }

    private void clean() {
        this.queue.removeIf(request -> {
            BlockState state = this.mc.world.getBlockState(request.pos);
            return !this.breakable(request.pos, state);
        });

        this.waiting.removeIf(retry -> {
            BlockState state = this.mc.world.getBlockState(retry.request.pos);
            return !this.breakable(retry.request.pos, state);
        });
    }

    private void fill() {
        if (this.paired) {
            if (this.primary != null || this.secondary != null) return;
            this.paired = false;
        }

        if (this.queue.isEmpty()) return;

        if (this.primary == null) {
            if (!this.startable()) return;

            Target target = this.next();
            if (target != null) this.begin(target);
        }

        if (this.primary == null || this.secondary != null
            || this.queue.isEmpty() || !this.parkable()) {
            return;
        }

        Target target = this.next();
        if (target == null) return;

        this.park();
        this.begin(target);
    }

    private Target next() {
        while (!this.queue.isEmpty()) {
            Request request = this.queue.removeFirst();

            BlockState state = this.mc.world.getBlockState(request.pos);
            if (!this.breakable(request.pos, state)) continue;

            Direction side = this.face(request.pos, request.side);
            return new Target(request, state, side);
        }
        return null;
    }

    private boolean startable() {
        return this.fast || System.currentTimeMillis() - this.stopped > restart;
    }

    private boolean parkable() {
        return this.secondary == null
            && System.currentTimeMillis() >= this.ready
            && this.primary != null && !this.primary.arming
            && !this.primary.finished && !this.primary.instant
            && this.primary.progress < 1.0;
    }

    private void park() {
        Target target = this.primary;

        this.action(target,
            PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, target.pos);

        Target parked = new Target(new Request(
            target.pos, target.side, target.retry
        ), target.state, target.side);

        long now = System.currentTimeMillis();

        parked.primary = false;
        parked.started = now;
        parked.updated = now;

        parked.slot = target.slot;
        parked.delta = this.delta(parked);
        parked.work = Math.max(0.0, parked.delta);

        parked.instant = parked.delta >= 1.0F;
        parked.progress = parked.instant ? 1.0 : this.progress(parked);
        parked.burst = target.burst;

        this.secondary = parked;
        this.primary = null;
        this.paired = true;
    }

    private void begin(Target target) {
        target.primary = true;

        target.side = this.face(target.pos, target.side);
        target.slot = this.best(target.state, target.pos);

        this.primary = target;

        int selected = this.mc.player.getInventory().selectedSlot;

        this.select(target.slot);

        if (selected != target.slot) {
            target.arming = true;
            target.arm = this.tick + this.arming.get();
            return;
        }

        this.start(target);
    }

    private void start(Target target) {
        long now = System.currentTimeMillis();

        target.arming = false;
        target.started = now;
        target.updated = now;

        target.delta = this.delta(target);
        target.work = Math.max(0.0, target.delta);

        target.instant = target.delta >= 1.0F;
        target.progress = target.instant ? 1.0 : this.progress(target);

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

        if (!state.equals(target.state)) {
            this.fail(target);
            return;
        }

        if (target.arming) {
            int slot = this.best(target.state, target.pos);
            int selected = this.mc.player.getInventory().selectedSlot;

            if (slot != target.slot || selected != slot) {
                target.slot = slot;
                this.select(slot);

                target.arm = this.tick + this.arming.get();
                return;
            }

            if (this.tick < target.arm) {
                return;
            }

            this.start(target);
            return;
        }

        if (target.finished) {
            int delay = target.primary ? this.valid.get() : this.valid.get() * 2;
            if (this.tick - target.finish >= delay) this.verify(target);
            return;
        }

        this.advance(target);

        int slot = this.best(target.state, target.pos);
        if (slot != target.slot) {
            target.slot = slot;
            target.delta = this.delta(target);
        }

        target.progress = this.progress(target);
        long elapsed = System.currentTimeMillis() - target.started;

        if (!target.burst && elapsed >= pause &&
            this.expected(target) >= pause && target.progress < 1.0) {
            this.burst(target);
        }

        if (target.progress >= 1.0) {
            this.finish(target);
        }
    }

    private void advance(Target target) {
        long now = System.currentTimeMillis();
        long elapsed = Math.max(0, now - target.updated);

        if (elapsed > 0 && target.delta > 0.0F) {
            target.work += target.delta * elapsed / 50.0;
        }

        target.updated = now;
    }

    private void finish(Target target) {
        if (target.finished) return;

        this.advance(target);

        target.progress = 1.0;
        target.finished = true;
        target.finish = this.tick;

        this.fast = target.burst;

        if (target.primary && !target.instant) {
            this.action(target,
                PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, target.pos);
        }

        this.stopped = System.currentTimeMillis();
    }

    private void verify(Target target) {
        BlockState state = this.mc.world.getBlockState(target.pos);

        if (state.isAir()) {
            this.confirm(target);
            return;
        }

        this.fail(target);
    }

    private void fail(Target target) {
        Direction side = this.face(target.pos, target.side);
        target.side = side;

        this.action(target,
            PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, target.pos);

        this.remove(target, false);

        if (target.retry >= this.retries.get()) {
            return;
        }

        long ready = System.currentTimeMillis();
        ready += this.cooldown.get() * 50L;

        this.waiting.addLast(new Retry(new Request(
            target.pos, side, target.retry + 1), ready)
        );
    }

    private void confirm(Target target) {
        this.last = new Request(target.pos,
            this.face(target.pos, target.side), 0
        );

        this.remove(target, true);
    }

    private void burst(Target target) {
        this.advance(target);

        target.side = this.face(target.pos, target.side);
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
        if (target.finished) return 1.0;

        double limit = this.limit(target);
        if (limit <= 0.0) return 1.0;

        return Math.min(1.0, target.work / limit);
    }

    private long expected(Target target) {
        if (target.delta <= 0.0F) return Long.MAX_VALUE;

        double limit = this.limit(target);
        return (long) Math.max(0.0, (limit / target.delta - 1.0) * 50.0);
    }

    private double limit(Target target) {
        return target.primary ? threshold : 1.0;
    }

    private float delta(Target target) {
        PlayerInventory inventory = this.mc.player.getInventory();
        int selected = inventory.selectedSlot;

        inventory.setSelectedSlot(target.slot);

        try {
            return target.state.calcBlockBreakingDelta(
                this.mc.player, this.mc.world, target.pos
            );
        } finally {
            inventory.setSelectedSlot(selected);
        }
    }

    private int best(BlockState state, BlockPos pos) {
        PlayerInventory inventory = this.mc.player.getInventory();

        int selected = inventory.selectedSlot;
        int best = selected;
        float speed = -1.0F;

        boolean suitable = false;
        boolean required = state.isToolRequired();

        try {
            for (int idx = 0; idx < 9; idx++) {
                ItemStack stack = inventory.getStack(idx);
                boolean good = stack.isSuitableFor(state);

                inventory.setSelectedSlot(idx);

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
            inventory.setSelectedSlot(selected);
        }

        return best;
    }

    private void action(Target target, PlayerActionC2SPacket.Action action, BlockPos pos) {
        target.side = this.face(target.pos, target.side);
        target.slot = this.best(target.state, target.pos);

        this.select(target.slot);
        this.packet(action, pos, target.side);
    }

    private void select(int slot) {
        PlayerInventory inventory = this.mc.player.getInventory();
        if (inventory.selectedSlot == slot) return;

        inventory.setSelectedSlot(slot);

        this.mc.player.networkHandler.sendPacket(
            new UpdateSelectedSlotC2SPacket(slot)
        );
    }

    private void packet(PlayerActionC2SPacket.Action action, BlockPos pos, Direction side) {
        if (this.mc.world == null || this.mc.interactionManager == null) {
            return;
        }

        ((InteractionAccessor) this.mc.interactionManager)
            .hyperglide$sendSequencedPacket(this.mc.world, sequence ->
                new PlayerActionC2SPacket(action, pos, side, sequence)
        );
    }

    private Direction face(BlockPos pos, Direction fallback) {
        Vec3d eye = this.mc.player.getEyePos();

        Direction best = fallback == null ? Direction.UP : fallback;
        double distance = Double.POSITIVE_INFINITY;

        for (Direction side : Direction.values()) {
            Vec3d point = this.point(pos, side);

            BlockHitResult hit = this.mc.world.raycast(
                new RaycastContext(eye, point, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, this.mc.player
                )
            );

            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) {
                continue;
            }

            double value = eye.squaredDistanceTo(point);
            if (value >= distance) continue;

            distance = value;
            best = hit.getSide();
        }

        if (distance < Double.POSITIVE_INFINITY) {
            return best;
        }

        for (Direction side : Direction.values()) {
            Vec3d point = this.point(pos, side);

            double value = eye.squaredDistanceTo(point);
            if (value >= distance) continue;

            distance = value;
            best = side;
        }

        return best;
    }

    private Vec3d point(BlockPos pos, Direction side) {
        return new Vec3d(
            pos.getX() + 0.5 + side.getOffsetX() * 0.49,
            pos.getY() + 0.5 + side.getOffsetY() * 0.49,
            pos.getZ() + 0.5 + side.getOffsetZ() * 0.49
        );
    }

    private BlockPos fake(BlockPos pos) {
        return new BlockPos(pos.getX(), height, pos.getZ());
    }

    private void remine() {
        if (!this.remine.get() || this.last == null ||
            this.primary != null || this.secondary != null ||
            !this.queue.isEmpty() || !this.waiting.isEmpty()) {
            return;
        }

        BlockState state = this.mc.world.getBlockState(this.last.pos);
        if (!this.breakable(this.last.pos, state)) return;

        this.queue.addLast(this.last);
    }

    private void remove(Target target, boolean confirmed) {
        if (target == this.primary) {
            this.primary = null;
        }

        if (target == this.secondary) {
            this.secondary = null;
            this.ready = System.currentTimeMillis();
            this.ready += (confirmed ? 50L : pause);
        }
    }

    private boolean tracked(BlockPos pos) {
        if (this.primary != null && this.primary.pos.equals(pos)) {
            return true;
        }

        if (this.secondary != null && this.secondary.pos.equals(pos)) {
            return true;
        }

        for (Request request : this.queue) {
            if (request.pos.equals(pos)) {
                return true;
            }
        }

        for (Retry retry : this.waiting) {
            if (retry.request.pos.equals(pos)) {
                return true;
            }
        }

        return false;
    }

    private boolean breakable(BlockPos pos, BlockState state) {
        return !state.isAir() && state.getHardness(this.mc.world, pos) >= 0.0F;
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

    private record Retry(Request request, long ready) {}

    private static class Target {
        private final BlockPos pos;
        private final BlockState state;
        private final int retry;

        private Direction side;

        private long started;
        private long updated;

        private float delta;
        private double work;
        private double progress;

        private int slot;

        private boolean primary;
        private boolean arming;
        private boolean burst;
        private boolean instant;
        private boolean finished;

        private int arm;
        private int finish;

        private Target(Request request, BlockState state, Direction side) {
            this.pos = request.pos;
            this.state = state;
            this.side = side;
            this.retry = request.retry;
        }
    }
}
