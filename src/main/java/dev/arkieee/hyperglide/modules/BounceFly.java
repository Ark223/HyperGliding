package dev.arkieee.hyperglide.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import dev.arkieee.hyperglide.Hyperglide;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.input.Input;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class BounceFly extends Module {
    private static final double stop = 0.2;
    private static final int grid = 10;
    private static final int reach = 5;
    private static final int wait = 20;
    private static final int warmup = 20;

    private final int[] dxs = {0, -1, -1, -1, 0, 1, 1, 1};
    private final int[] dzs = {1, 1, 0, -1, -1, -1, 0, 1};

    private final SettingGroup general = this.settings.getDefaultGroup();

    private final Setting<Double> pitch = this.general.add(new DoubleSetting.Builder()
        .name("pitch")
        .description("The pitch used while bouncing.")
        .defaultValue(72.4)
        .sliderRange(-90, 90)
        .decimalPlaces(2)
        .build()
    );

    private final Setting<Boolean> obstacle = this.general.add(new BoolSetting.Builder()
        .name("obstacle-passer")
        .description("Uses Baritone to pass obstacles when movement stops.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> avoid = this.general.add(new BoolSetting.Builder()
        .name("avoid-collisions")
        .description("Uses raycasts to detect obstacles and avoid collisions.")
        .defaultValue(false)
        .visible(this.obstacle::get)
        .build()
    );

    private final Setting<Integer> ticks = this.general.add(new IntSetting.Builder()
        .name("collision-ticks")
        .description("How many movement ticks ahead to scan for obstacles.")
        .defaultValue(8)
        .range(3, 10)
        .sliderRange(3, 10)
        .visible(() -> this.obstacle.get() && this.avoid.get())
        .build()
    );

    private int level;
    private int x;
    private int z;
    private int dx;
    private int dz;
    private int slow;
    private int warm;
    private boolean pass;

    public BounceFly() {
        super(Hyperglide.CATEGORY, "bounce-fly",
            "Uses elytra bouncing for fast highway travel."
        );
    }

    @Override
    public void onActivate() {
        if (this.mc.player == null ||
            this.mc.world == null) return;

        this.level = this.mc.player.getBlockY();
        this.x = this.snap(this.mc.player.getX());
        this.z = this.snap(this.mc.player.getZ());

        this.slow = 0;
        this.warm = 0;
        this.pass = false;

        this.mc.player.setSprinting(false);
        this.face();
    }

    @Override
    public void onDeactivate() {
        this.mc.options.forwardKey.setPressed(false);
        this.mc.options.jumpKey.setPressed(false);

        if (this.mc.player != null) {
            this.mc.player.setSprinting(false);
        }

        if (this.pass) {
            this.baritone().getPathingBehavior().cancelEverything();
        }

        this.slow = 0;
        this.warm = 0;
        this.pass = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (this.mc.player == null ||
            this.mc.world == null) return;

        IBaritone baritone = null;

        if (this.obstacle.get()) {
            baritone = this.baritone();

            if (this.pass && this.pathing(baritone)) {
                this.mc.options.forwardKey.setPressed(false);
                this.mc.options.jumpKey.setPressed(false);
                this.mc.player.setSprinting(false);

                this.slow = 0;
                this.warm = 0;
                return;
            }

            if (this.pass) {
                this.rotate();

                this.slow = 0;
                this.warm = 0;
                this.pass = false;
            }
        } else if (this.pass) {
            this.baritone().getPathingBehavior().cancelEverything();

            this.slow = 0;
            this.warm = 0;
            this.pass = false;
        }

        if (!this.elytra()) {
            this.mc.options.forwardKey.setPressed(false);
            this.mc.options.jumpKey.setPressed(false);
            this.mc.player.setSprinting(false);

            this.slow = 0;
            this.warm = 0;
            return;
        }

        this.mc.player.setPitch(this.pitch.get().floatValue());

        this.mc.options.forwardKey.setPressed(true);
        this.mc.options.jumpKey.setPressed(true);

        if (this.obstacle.get() && this.avoid.get()) {
            Vec3d hit = this.collision();

            if (hit != null) {
                this.path(baritone, hit);
                return;
            }
        }

        this.warm++;
        this.mc.player.setSprinting(this.warm >= warmup);

        if (this.obstacle.get() && this.warm >= warmup) {
            Vec3d vel = this.mc.player.getVelocity();
            double speed = Math.hypot(vel.x, vel.z);

            if (speed < stop) this.slow++;
            else this.slow = 0;

            if (this.slow > wait) {
                this.path(baritone);
                return;
            }
        } else {
            this.slow = 0;
        }

        if (!this.mc.player.isGliding()) {
            this.mc.player.startGliding();

            this.mc.player.networkHandler.sendPacket(
                new ClientCommandC2SPacket(this.mc.player,
                    ClientCommandC2SPacket.Mode.START_FALL_FLYING)
            );
        }
    }

    public void input(Input input) {
        if (!this.isActive() || this.mc.player == null
            || this.pass || !this.elytra()) return;

        PlayerInput state = input.playerInput;

        input.playerInput = new PlayerInput(
            true, false, state.left(), state.right(),
            true, state.sneak(), this.warm >= warmup
        );

        input.movementForward = 1.0F;
    }

    private Vec3d collision() {
        Vec3d front = new Vec3d(this.dx, 0, this.dz).normalize();
        Vec3d side = new Vec3d(-front.z, 0, front.x);
        Vec3d vel = this.mc.player.getVelocity();

        double scan = Math.hypot(vel.x, vel.z) * this.ticks.get();
        double width = this.mc.player.getWidth() / 2.0;
        double distance = Double.MAX_VALUE;
        Vec3d closest = null;

        for (int idx = -1; idx <= 1; idx += 2) {
            for (double y = 0.5; y <= 1.5; y++) {
                Vec3d start = new Vec3d(this.mc.player.getX(),
                    this.level + y, this.mc.player.getZ())
                    .add(side.multiply(width * idx));

                Vec3d end = start.add(front.multiply(scan));
                BlockHitResult hit = this.ray(start, end);

                if (hit.getType() != HitResult.Type.BLOCK) continue;

                double current = start.squaredDistanceTo(hit.getPos());

                if (current < distance) {
                    distance = current;
                    closest = hit.getPos();
                }
            }
        }
        return closest;
    }

    private BlockHitResult ray(Vec3d start, Vec3d end) {
        return this.mc.world.raycast(new RaycastContext(
            start, end, RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE, this.mc.player)
        );
    }

    private void path(IBaritone baritone) {
        this.path(baritone, this.mc.player.getPos());
    }

    private void path(IBaritone baritone, Vec3d point) {
        this.mc.options.forwardKey.setPressed(false);
        this.mc.options.jumpKey.setPressed(false);

        this.slow = 0;
        this.warm = 0;
        this.pass = true;

        this.mc.player.setSprinting(false);

        GoalBlock goal = new GoalBlock(this.goal(point));
        baritone.getCustomGoalProcess().setGoalAndPath(goal);
    }

    private BlockPos goal(Vec3d point) {
        Vec3d dir = new Vec3d(this.dx, 0, this.dz).normalize();

        double ox = point.x - this.x;
        double oz = point.z - this.z;
        double along = ox * dir.x + oz * dir.z;

        double x = this.x + dir.x * (along + reach);
        double z = this.z + dir.z * (along + reach);

        return new BlockPos((int) Math.round(x),
            this.level, (int) Math.round(z));
    }

    private void face() {
        float yaw = this.mc.player.getYaw();
        int face = MathHelper.floor((yaw + 22.5F) / 45.0F) & 7;

        this.dx = this.dxs[face];
        this.dz = this.dzs[face];
    }

    private void rotate() {
        float yaw = (float) Math.toDegrees(
            Math.atan2(-this.dx, this.dz));

        this.mc.player.setYaw(yaw);
        this.mc.player.setHeadYaw(yaw);
        this.mc.player.setBodyYaw(yaw);
    }

    private int snap(double value) {
        return (int) Math.round(value / grid) * grid;
    }

    private boolean pathing(IBaritone baritone) {
        return baritone.getCustomGoalProcess().isActive()
            || baritone.getPathingBehavior().isPathing();
    }

    private IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    private boolean elytra() {
        return this.mc.player.getEquippedStack(
            EquipmentSlot.CHEST).isOf(Items.ELYTRA);
    }
}
