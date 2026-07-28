package dev.arkieee.hyperglide.modules;

import dev.arkieee.hyperglide.Hyperglide;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.AbstractDonkeyEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.vehicle.VehicleInventory;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import java.util.Optional;

public class EasyAccess extends Module {
    private static final double edge = 0.001;

    private final SettingGroup general = this.settings.getDefaultGroup();

    private final Setting<Double> range = this.general.add(
        new DoubleSetting.Builder()
            .name("max-range")
            .description("Maximum container interaction range.")
            .defaultValue(4.5)
            .min(1)
            .sliderMax(6.0)
            .build()
    );

    private boolean lock;
    private boolean block;
    private boolean own;

    public EasyAccess() {
        super(Hyperglide.CATEGORY, "easy-access",
            "Opens hidden containers within interaction range."
        );
    }

    @Override
    public void onActivate() {
        this.lock = this.mc.options.useKey.isPressed();
        this.block = false;
        this.own = false;
    }

    @Override
    public void onDeactivate() {
        this.lock = false;
        this.block = false;
        this.own = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (this.mc.player == null || this.mc.world == null ||
            this.mc.interactionManager == null) {
            return;
        }

        if (!this.mc.options.useKey.isPressed()) {
            this.lock = false;
            this.block = false;
            return;
        }

        if (this.lock) return;

        this.lock = true;
        this.block = false;

        if (this.visible()) return;

        Target target = this.target();
        if (target == null) return;

        this.block = true;
        this.own = true;

        try {
            if (target.entity != null) {
                this.entity(target.entity);
            } else {
                this.mc.interactionManager.interactBlock(
                    this.mc.player, Hand.MAIN_HAND, target.block
                );
            }
        } finally {
            this.own = false;
        }

        this.mc.player.swingHand(Hand.MAIN_HAND);
    }

    @EventHandler
    private void onPacket(PacketEvent.Send event) {
        if (this.block && !this.own &&
            event.packet instanceof PlayerInteractBlockC2SPacket) {
            event.cancel();
        }
    }

    private boolean visible() {
        if (this.mc.crosshairTarget instanceof BlockHitResult hit
            && hit.getType() == HitResult.Type.BLOCK) {
            return this.container(hit.getBlockPos());
        }
        if (this.mc.crosshairTarget instanceof EntityHitResult hit) {
            return this.container(hit.getEntity());
        }
        return false;
    }

    private Target target() {
        double reach = this.range.get();

        Vec3d eye = this.mc.player.getEyePos();
        Vec3d look = this.mc.player.getRotationVec(1.0F);
        Vec3d end = eye.add(look.multiply(reach));

        BlockPos center = BlockPos.ofFloored(eye);
        int radius = (int) Math.ceil(reach);

        Target best = null;
        double distance = Double.MAX_VALUE;

        for (BlockPos scan : BlockPos.iterateOutwards(
            center, radius, radius, radius
        )) {
            BlockPos pos = scan.toImmutable();
            if (!this.container(pos)) continue;

            Optional<Vec3d> result = new Box(pos)
                .expand(edge).raycast(eye, end);
            if (result.isEmpty()) continue;

            Vec3d point = result.get();
            double current = eye.squaredDistanceTo(point);

            if (current > reach * reach ||
                this.visible(pos, eye, point) ||
                current >= distance) {
                continue;
            }

            BlockHitResult hit = new BlockHitResult(
                point, this.side(pos, eye), pos, false);

            best = new Target(hit, null);
            distance = current;
        }

        for (Entity entity : this.mc.world.getEntities()) {
            if (!this.container(entity)) continue;

            Optional<Vec3d> result = entity.getBoundingBox()
                .expand(edge).raycast(eye, end);
            if (result.isEmpty()) continue;

            Vec3d point = result.get();
            double current = eye.squaredDistanceTo(point);

            if (current > reach * reach ||
                this.visible(eye, point) ||
                current >= distance) {
                continue;
            }

            best = new Target(null, entity);
            distance = current;
        }

        return best;
    }

    private boolean visible(BlockPos pos, Vec3d eye, Vec3d point) {
        Vec3d dir = point.subtract(eye);

        if (dir.lengthSquared() > 0) {
            point = point.add(dir.normalize().multiply(edge));
        }

        BlockHitResult hit = this.mc.world.raycast(
            new RaycastContext(eye, point,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                this.mc.player
            )
        );

        return hit.getType() == HitResult.Type.BLOCK
            && hit.getBlockPos().equals(pos);
    }

    private boolean visible(Vec3d eye, Vec3d point) {
        Vec3d dir = point.subtract(eye);

        if (dir.lengthSquared() > 0) {
            point = point.subtract(dir.normalize().multiply(edge));
        }

        BlockHitResult hit = this.mc.world.raycast(
            new RaycastContext(eye, point,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                this.mc.player
            )
        );

        return hit.getType() == HitResult.Type.MISS;
    }

    private boolean container(Entity entity) {
        if (!entity.isAlive() || entity.isSpectator()) return false;

        if (entity instanceof VehicleInventory) return true;
        if (entity instanceof MerchantEntity) return true;

        return entity instanceof AbstractDonkeyEntity donkey
            && donkey.isTame() && donkey.hasChest();
    }

    private void entity(Entity entity) {
        if (!(entity instanceof AbstractDonkeyEntity)) {
            this.mc.interactionManager.interactEntity(
                this.mc.player, entity, Hand.MAIN_HAND
            );
            return;
        }

        boolean sneak = this.mc.player.isSneaking();

        if (!sneak) {
            this.mc.player.networkHandler.sendPacket(
                new ClientCommandC2SPacket(this.mc.player,
                    ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY)
            );
        }

        this.mc.interactionManager.interactEntity(
            this.mc.player, entity, Hand.MAIN_HAND
        );

        if (!sneak) {
            this.mc.player.networkHandler.sendPacket(
                new ClientCommandC2SPacket(this.mc.player,
                    ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY)
            );
        }
    }

    private boolean container(BlockPos pos) {
        BlockState state = this.mc.world.getBlockState(pos);

        return state.isOf(Blocks.ENDER_CHEST) ||
            state.createScreenHandlerFactory(this.mc.world, pos) != null;
    }

    private Direction side(BlockPos pos, Vec3d eye) {
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d dir = eye.subtract(center);

        Direction best = Direction.UP;
        double value = -Double.MAX_VALUE;

        for (Direction side : Direction.values()) {
            double current =
                dir.x * side.getOffsetX() +
                dir.y * side.getOffsetY() +
                dir.z * side.getOffsetZ();

            if (current > value) {
                best = side;
                value = current;
            }
        }
        return best;
    }

    private record Target(BlockHitResult block, Entity entity) {}
}
