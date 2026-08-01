package dev.arkieee.hyperglide.modules;

import dev.arkieee.hyperglide.Hyperglide;
import dev.arkieee.hyperglide.mixin.InteractionAccessor;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class ControlFly extends Module {
    private static final double epsilon = 1.0E-6;
    private static final double vertical = 0.15;
    private static final double ticks = 20.0;

    private static final int priority = 100;
    private static final int timeout = 4;
    private static final float bound = 60.0F;

    private static final double correction = 0.025;
    private static final double gain = 0.30;
    private static final double lift = 0.004;

    private final SettingGroup movement = this.settings.createGroup("Movement");
    private final SettingGroup auto = this.settings.createGroup("Automation");

    private final Setting<Double> maximum = this.movement.add(new DoubleSetting.Builder()
        .name("maximum-speed")
        .description("Maximum controlled speed in blocks per second.")
        .defaultValue(34.0)
        .min(0.0)
        .sliderMax(40.0)
        .build());

    private final Setting<Double> minimum = this.movement.add(new DoubleSetting.Builder()
        .name("minimum-speed")
        .description("Uses a rocket when speed drops below this value.")
        .defaultValue(30.0)
        .min(0.0)
        .sliderMax(40.0)
        .build());

    private final Setting<Double> penalty = this.movement.add(new DoubleSetting.Builder()
        .name("ascent-penalty")
        .description("Speed removed while flying nearly straight up.")
        .defaultValue(2.0)
        .min(0.0)
        .sliderMax(5.0)
        .build());

    private final Setting<Boolean> gravity = this.movement.add(new BoolSetting.Builder()
        .name("no-gravity")
        .description("Disables gravity during horizontal movement.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> forward = this.auto.add(new BoolSetting.Builder()
        .name("keep-forward")
        .description("Moves forward when no movement key is held.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> starter = this.auto.add(new BoolSetting.Builder()
        .name("auto-takeoff")
        .description("Starts gliding after holding jump while airborne.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> timer = this.auto.add(new IntSetting.Builder()
        .name("takeoff-timer")
        .description("Jump hold ticks required before starting flight.")
        .defaultValue(5)
        .min(1)
        .sliderMax(10)
        .visible(this.starter::get)
        .build());

    private final Boost boost = new Boost();
    private final Flight flight = new Flight();
    private final Turn turn = new Turn();
    private final View view = new View();

    private int jump;

    public ControlFly() {
        super(Hyperglide.CATEGORY, "control-fly",
            "Provides controlled elytra flight with automatic rocket boosting."
        );
    }

    @Override
    public void onActivate() {
        this.reset();
        if (this.mc.player == null) return;

        this.view.yaw = this.mc.player.getYaw();
        this.view.pitch = this.mc.player.getPitch();

        this.flight.yaw = this.view.yaw;
        this.flight.pitch = this.view.pitch;
        this.flight.altitude = this.mc.player.getY();
    }

    @Override
    public void onDeactivate() {
        this.restore();
        this.reset();
    }

    @EventHandler
    private void tick(TickEvent.Pre event) {
        if (!this.valid()) return;

        this.update();

        if (!this.mc.player.isGliding()) {
            this.restore();
            this.clear();
            this.takeoff();
            return;
        }

        this.view();
        this.takeoff();
        this.control();
    }

    @EventHandler
    private void move(PlayerMoveEvent event) {
        if (!this.valid()
            || event.type != MovementType.SELF
            || !this.mc.player.isGliding()) return;

        Vec3d input = this.direction();
        if (input.lengthSquared() < epsilon) {
            this.stop(event);
            return;
        }

        this.limit(event, input.normalize());
    }

    @EventHandler
    private void packet(PacketEvent.Send event) {
        if (!this.valid() || this.boost.automatic || !this.mc.player.isGliding()
            || !(event.packet instanceof PlayerInteractItemC2SPacket packet)) {
            return;
        }

        ItemStack stack = this.mc.player.getStackInHand(packet.getHand());
        if (stack.isOf(Items.FIREWORK_ROCKET)) this.await();
    }

    private void control() {
        Vec3d input = this.direction();
        if (input.lengthSquared() < epsilon) {
            this.idle();
            return;
        }

        input = input.normalize();

        this.flight.manual =
            this.mc.options.jumpKey.isPressed() ||
            this.mc.options.sneakKey.isPressed();

        Vec3d dir = this.steer(input);
        boolean boosted = this.active();

        this.aim(dir, boosted);

        boolean launch = this.launch(dir, boosted);
        if (launch) this.prepare();

        this.mc.player.setYaw(this.flight.yaw);
        this.mc.player.setPitch(this.flight.pitch);

        this.rotate(launch);
    }

    private void aim(Vec3d dir, boolean boosted) {
        if (this.flight.manual) {
            this.flight.leveling = false;
            this.flight.altitude = this.mc.player.getY();
            this.flight.pitch = this.angle(dir);
            return;
        }

        if (!this.flight.leveling) {
            this.flight.altitude = this.mc.player.getY();
            this.flight.leveling = true;
        }

        this.flight.pitch = !this.gravity.get() ? 0.0F :
            this.level(this.mc.player.getVelocity(), boosted);
    }

    private boolean launch(Vec3d dir, boolean boosted) {
        Vec3d next = this.predict(
            this.mc.player.getVelocity(), this.flight.yaw,
            this.flight.pitch, boosted
        );

        double low = Math.min(
            this.minimum.get() / ticks, this.maximum(dir)
        );

        return !this.busy() && next.length() < low && this.stocked();
    }

    private void prepare() {
        this.await();

        if (!this.flight.manual && this.gravity.get()) {
            this.flight.pitch = this.level(
                this.mc.player.getVelocity(), true
            );
        }
    }

    private void stop(PlayerMoveEvent event) {
        ((IVec3d) event.movement).meteor$set(0.0, 0.0, 0.0);
        this.mc.player.setVelocity(Vec3d.ZERO);
    }

    private void limit(PlayerMoveEvent event, Vec3d dir) {
        double maximum = this.maximum(dir);

        double horizontal = event.movement.horizontalLength();
        double amount = horizontal;

        if (horizontal > maximum) {
            amount = maximum;
        } else if (this.active()) {
            amount = Math.min(maximum, horizontal + correction);
        }

        if (Math.abs(amount - horizontal) <= epsilon) return;

        Vec3d flat = this.flat(event.movement, amount, horizontal);
        Vec3d adjusted = new Vec3d(flat.x, event.movement.y, flat.z);

        ((IVec3d) event.movement).meteor$set(
            adjusted.x, adjusted.y, adjusted.z
        );

        this.mc.player.setVelocity(adjusted);
    }

    private Vec3d flat(Vec3d movement, double amount, double horizontal) {
        if (horizontal > epsilon) {
            return new Vec3d(movement.x, 0.0, movement.z)
                .multiply(amount / horizontal);
        }

        return Vec3d.fromPolar(0.0F, this.flight.yaw).multiply(amount);
    }

    private void update() {
        if (this.boost.pending && this.mc.player.age > this.boost.expiry) {
            this.boost.pending = false;
            this.boost.launching = false;
        }

        if (this.boost.rocket != null && !this.boost.rocket.isAlive()) {
            this.boost.rocket = null;
        }
    }

    private void await() {
        this.boost.pending = true;
        this.boost.expiry = this.mc.player.age + timeout;
    }

    private void takeoff() {
        if (!this.starter.get() || this.mc.player.isGliding()) {
            this.jump = 0;
            return;
        }

        if (!this.mc.options.jumpKey.isPressed()) {
            this.jump = 0;
            return;
        }

        this.jump++;

        if (this.jump < this.timer.get()
            || this.mc.player.isOnGround()
            || !this.glider()) return;

        this.jump = 0;

        this.mc.getNetworkHandler().sendPacket(
            new ClientCommandC2SPacket(this.mc.player,
                ClientCommandC2SPacket.Mode.START_FALL_FLYING
            )
        );
    }

    private boolean glider() {
        return this.mc.player.getEquippedStack(EquipmentSlot.CHEST)
            .contains(DataComponentTypes.GLIDER);
    }

    private Vec3d direction() {
        Vec3d dir = Vec3d.ZERO;

        float angle = this.view.active ? this.view.yaw : this.mc.player.getYaw();

        Vec3d front = Vec3d.fromPolar(0.0F, angle);
        Vec3d right = Vec3d.fromPolar(0.0F, angle + 90.0F);

        if (this.mc.options.forwardKey.isPressed()) dir = dir.add(front);
        if (this.mc.options.backKey.isPressed()) dir = dir.subtract(front);
        if (this.mc.options.rightKey.isPressed()) dir = dir.add(right);
        if (this.mc.options.leftKey.isPressed()) dir = dir.subtract(right);
        if (this.mc.options.jumpKey.isPressed()) dir = dir.add(0.0, 1.0, 0.0);
        if (this.mc.options.sneakKey.isPressed()) dir = dir.add(0.0, -1.0, 0.0);

        if (dir.lengthSquared() < epsilon && this.forward.get()) return front;
        return dir;
    }

    private Vec3d steer(Vec3d input) {
        double horizontal = Math.hypot(input.x, input.z);
        if (horizontal < epsilon) {
            if (!this.flight.steering) {
                this.flight.yaw = this.mc.player.getYaw();
                this.flight.steering = true;
            }
            return input;
        }

        this.flight.steering = true;
        this.flight.yaw = (float) (Math.toDegrees(
            Math.atan2(input.z, input.x)) - 90.0
        );

        Vec3d flat = Vec3d.fromPolar(
            0.0F, this.flight.yaw).multiply(horizontal);
        return flat.add(0.0, input.y, 0.0).normalize();
    }

    private float angle(Vec3d dir) {
        return MathHelper.clamp((float) -Math.toDegrees(
            Math.atan2(dir.y, Math.hypot(dir.x, dir.z))
        ), -90.0F, 90.0F);
    }

    private float level(Vec3d velocity, boolean boosted) {
        double error = this.flight.altitude - this.mc.player.getY();
        double desired = MathHelper.clamp(error * gain + lift, -0.20, 0.20);

        Choice choice = new Choice(
            this.flight.pitch, Double.POSITIVE_INFINITY
        );

        choice = this.search(velocity, boosted,
            desired, -bound, 1.0F, 80, choice
        );

        choice = this.search(velocity, boosted, desired,
            choice.pitch() - 1.0F, 0.1F, 20, choice
        );

        return choice.pitch();
    }

    private Choice search(Vec3d velocity, boolean boosted,
        double desired, float start, float step, int count, Choice choice) {

        for (int index = 0; index <= count; index++) {
            float pitch = MathHelper.clamp(start + index * step, -bound, 20.0F);

            double score = this.score(velocity, pitch, desired, boosted);
            if (score < choice.score()) choice = new Choice(pitch, score);
        }

        return choice;
    }

    private double score(Vec3d velocity, float pitch, double desired, boolean boosted) {
        Vec3d next = this.predict(velocity, this.flight.yaw, pitch, boosted);
        double score = Math.abs(next.y - desired);

        score += Math.max(0.0,
            this.minimum.get() / ticks - next.horizontalLength()
        ) * 0.01;

        score += Math.abs(pitch - this.flight.pitch) * 1.0E-5;
        return score;
    }

    private void rotate(boolean launch) {
        boolean changed = this.changed();

        if (launch) {
            float yaw = this.flight.yaw;
            float pitch = this.flight.pitch;

            this.boost.launching = true;

            Rotations.rotate(yaw, pitch, priority, () -> this.rocket(yaw, pitch));
            changed = true;
        } else if (changed) {
            Rotations.rotate(this.flight.yaw, this.flight.pitch, priority);
        }

        if (changed) this.remember();
    }

    private boolean changed() {
        return !this.turn.active || Math.abs(MathHelper.wrapDegrees(
            this.flight.yaw - this.turn.yaw)) > 0.05F || Math.abs(
            this.flight.pitch - this.turn.pitch) > 0.05F;
    }

    private void remember() {
        this.turn.yaw = this.flight.yaw;
        this.turn.pitch = this.flight.pitch;
        this.turn.active = true;
    }

    private double maximum(Vec3d dir) {
        double speed = this.maximum.get() / ticks;

        if (dir.y > 0.0 && Math.hypot(dir.x, dir.z) <= vertical) {
            speed -= this.penalty.get() / ticks;
        }

        return Math.max(0.0, speed);
    }

    private Vec3d predict(Vec3d velocity, float yaw, float pitch, boolean boosted) {
        Vec3d look = Vec3d.fromPolar(pitch, yaw);
        Vec3d next = this.glide(velocity, look);

        return boosted ? this.firework(next, look) : next;
    }

    private Vec3d glide(Vec3d velocity, Vec3d look) {
        double horizontal = Math.hypot(look.x, look.z);
        double speed = velocity.horizontalLength();

        double cosine = horizontal * horizontal
            * Math.min(1.0, look.length() / 0.4);

        velocity = velocity.add(0.0, -0.08 + cosine * 0.06, 0.0);

        velocity = this.fall(velocity, look, horizontal, cosine);
        velocity = this.rise(velocity, look, horizontal, speed);
        velocity = this.align(velocity, look, horizontal, speed);

        return velocity.multiply(0.99, 0.98, 0.99);
    }

    private Vec3d fall(Vec3d velocity, Vec3d look, double horizontal, double cosine) {
        if (velocity.y < 0.0 && horizontal > 0.0) {
            double lift = velocity.y * -0.1 * cosine;

            velocity = velocity.add(
                look.x * lift / horizontal,
                lift,
                look.z * lift / horizontal
            );
        }
        return velocity;
    }

    private Vec3d rise(Vec3d velocity, Vec3d look, double horizontal, double speed) {
        double angle = Math.asin(MathHelper.clamp(-look.y, -1.0, 1.0));

        if (angle < 0.0 && horizontal > 0.0) {
            double lift = speed * -Math.sin(angle) * 0.04;

            velocity = velocity.add(
                -look.x * lift / horizontal,
                lift * 3.2,
                -look.z * lift / horizontal
            );
        }

        return velocity;
    }

    private Vec3d align(Vec3d velocity, Vec3d look, double horizontal, double speed) {
        if (horizontal > 0.0) {
            velocity = velocity.add(
                (look.x / horizontal * speed - velocity.x) * 0.1,
                0.0,
                (look.z / horizontal * speed - velocity.z) * 0.1
            );
        }
        return velocity;
    }

    private Vec3d firework(Vec3d velocity, Vec3d look) {
        return velocity.add(
            this.thrust(velocity.x, look.x),
            this.thrust(velocity.y, look.y),
            this.thrust(velocity.z, look.z)
        );
    }

    private double thrust(double velocity, double look) {
        return look * 0.1 + (look * 1.5 - velocity) * 0.5;
    }

    private boolean stocked() {
        return InvUtils.findInHotbar(Items.FIREWORK_ROCKET).found();
    }

    private void rocket(float yaw, float pitch) {
        if (!this.valid() || this.mc.interactionManager == null) {
            this.cancel();
            return;
        }

        FindItemResult result = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (!result.found()) {
            this.cancel();
            return;
        }

        Hand hand = result.isOffhand() ? Hand.OFF_HAND : Hand.MAIN_HAND;

        ItemStack stack = this.stack(result);
        if (!stack.isOf(Items.FIREWORK_ROCKET)) {
            this.cancel();
            return;
        }

        int selected = this.mc.player.getInventory().selectedSlot;
        boolean swap = !result.isOffhand() && result.slot() != selected;

        this.boost.automatic = true;

        try {
            if (swap) this.select(result.slot());
            this.send(hand, yaw, pitch);
        } finally {
            if (swap) this.select(selected);
            this.boost.automatic = false;
        }

        this.boost.expiry = this.mc.player.age + timeout;
        this.boost.launching = false;
    }

    private ItemStack stack(FindItemResult result) {
        return result.isOffhand() ? this.mc.player.getOffHandStack()
            : this.mc.player.getInventory().getStack(result.slot());
    }

    private void select(int slot) {
        this.mc.getNetworkHandler().sendPacket(
            new UpdateSelectedSlotC2SPacket(slot)
        );
    }

    private void send(Hand hand, float yaw, float pitch) {
        ((InteractionAccessor) this.mc.interactionManager)
            .hyperglide$sendSequencedPacket(this.mc.world,
                sequence -> new PlayerInteractItemC2SPacket(
                    hand, sequence, yaw, pitch
                )
            );
    }

    private void cancel() {
        this.boost.pending = false;
        this.boost.launching = false;
    }

    public void track(FireworkRocketEntity rocket) {
        if (!this.valid()) return;

        this.boost.rocket = rocket;
        this.boost.pending = false;
        this.boost.launching = false;
    }

    private boolean active() {
        return this.boost.rocket != null && this.boost.rocket.isAlive();
    }

    private boolean busy() {
        return this.boost.pending || this.boost.launching || this.active();
    }

    public boolean view() {
        if (!this.isActive() || this.mc.player == null
            || !this.mc.player.isGliding()) return false;

        if (!this.view.active) {
            this.view.yaw = this.mc.player.getYaw();
            this.view.pitch = this.mc.player.getPitch();
            this.view.active = true;
        }

        return true;
    }

    public void look(double x, double y) {
        this.view.yaw += (float) (x * 0.15);

        this.view.pitch = MathHelper.clamp(
            this.view.pitch + (float) (y * 0.15), -90.0F, 90.0F
        );
    }

    public boolean camera() {
        return this.isActive() && this.view.active &&
            this.mc.player != null && this.mc.player.isGliding();
    }

    public float yaw() {
        return this.view.yaw;
    }

    public float pitch() {
        return this.view.pitch;
    }

    private void restore() {
        if (!this.view.active) return;

        if (this.mc.player != null) {
            this.mc.player.setYaw(this.view.yaw);
            this.mc.player.setPitch(this.view.pitch);
        }

        this.view.active = false;
    }

    private void idle() {
        this.flight.manual = false;
        this.flight.leveling = false;
        this.flight.steering = false;
    }

    private boolean valid() {
        return this.mc.player != null
            && this.mc.world != null
            && this.mc.getNetworkHandler() != null;
    }

    private void clear() {
        this.boost.expiry = 0;
        this.boost.pending = false;
        this.boost.launching = false;
        this.boost.automatic = false;
        this.boost.rocket = null;

        this.flight.manual = false;
        this.flight.leveling = false;
        this.flight.steering = false;

        this.flight.altitude =
            this.mc.player == null ?
            0.0 : this.mc.player.getY();

        this.turn.active = false;
    }

    private void reset() {
        this.clear();
        this.jump = 0;

        this.flight.yaw = 0.0F;
        this.flight.pitch = 0.0F;
        this.flight.altitude = 0.0;

        this.view.active = false;
        this.view.yaw = 0.0F;
        this.view.pitch = 0.0F;

        this.turn.active = false;
        this.turn.yaw = 0.0F;
        this.turn.pitch = 0.0F;
    }

    private record Choice(float pitch, double score) {}

    private static class Flight {
        private boolean manual;
        private boolean leveling;
        private boolean steering;

        private double altitude;
        private float yaw;
        private float pitch;
    }

    private static class View {
        private boolean active;
        private float yaw;
        private float pitch;
    }

    private static class Boost {
        private int expiry;

        private boolean pending;
        private boolean launching;
        private boolean automatic;

        private FireworkRocketEntity rocket;
    }

    private static class Turn {
        private boolean active;
        private float yaw;
        private float pitch;
    }
}
