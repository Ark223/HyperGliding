package dev.arkieee.hyperglide.modules;

import dev.arkieee.hyperglide.Hyperglide;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class AirPlace extends Module {
    private final SettingGroup general = this.settings.getDefaultGroup();
    private final SettingGroup visuals = this.settings.createGroup("Visuals");

    private final Setting<Double> range = this.general.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far Air Place can target.")
        .defaultValue(3.0)
        .min(0.0)
        .sliderMax(6.0)
        .build()
    );

    private final Setting<Boolean> render = this.visuals.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders the air-place target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shape = this.visuals.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape")
        .description("How the target box is rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> side = this.visuals.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The fill color of the target box.")
        .defaultValue(new SettingColor(255, 255, 255, 32))
        .build()
    );

    private final Setting<SettingColor> line = this.visuals.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The outline color of the target box.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private BlockHitResult hit;
    private boolean lock;
    private boolean own;

    public AirPlace() {
        super(Hyperglide.CATEGORY, "air-place",
            "Places one block in the air per right-click."
        );
    }

    @Override
    public void onActivate() {
        this.hit = null;
        this.lock = this.mc.options.useKey.isPressed();
        this.own = false;
    }

    @Override
    public void onDeactivate() {
        this.hit = null;
        this.lock = false;
        this.own = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (this.mc.player == null ||
            this.mc.world == null ||
            this.mc.getCameraEntity() == null) {
            this.hit = null;
            this.lock = false;
            return;
        }

        boolean pressed = this.mc.options.useKey.isPressed();
        if (!pressed) this.lock = false;

        ItemStack stack = this.mc.player.getMainHandStack();

        if (!this.valid(stack)) {
            this.hit = null;
            return;
        }

        if (this.mc.crosshairTarget != null &&
            this.mc.crosshairTarget.getType() != HitResult.Type.MISS) {
            this.hit = null;
            return;
        }

        HitResult ray = this.mc.getCameraEntity().raycast(
            this.range.get(), 0.0F, false
        );

        if (ray instanceof BlockHitResult block &&
            this.mc.world.getBlockState(block.getBlockPos()).isReplaceable()) {
            this.hit = block;
        } else {
            this.hit = null;
        }

        if (!pressed || this.lock || this.hit == null) return;

        this.lock = true;
        this.place(this.hit, stack);
    }

    @EventHandler
    private void onPacket(PacketEvent.Send event) {
        if (this.lock && !this.own &&
            event.packet instanceof PlayerInteractBlockC2SPacket) {
            event.cancel();
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!this.render.get() || this.hit == null ||
            this.mc.player == null || this.mc.world == null ||
            !this.valid(this.mc.player.getMainHandStack()) ||
            !this.mc.world.getBlockState(this.hit.getBlockPos()).isReplaceable()) {
            return;
        }

        event.renderer.box(this.hit.getBlockPos(),
            this.side.get(), this.line.get(), this.shape.get(), 0
        );
    }

    private void place(BlockHitResult hit, ItemStack stack) {
        PlayerActionC2SPacket swap = new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
            BlockPos.ORIGIN, Direction.DOWN
        );

        this.mc.player.networkHandler.sendPacket(swap);
        this.own = true;

        try {
            this.mc.player.networkHandler.sendPacket(
                new PlayerInteractBlockC2SPacket(Hand.OFF_HAND, hit,
                    this.mc.player.currentScreenHandler.getRevision() + 2
                )
            );
        } finally {
            this.own = false;
        }

        this.mc.player.networkHandler.sendPacket(swap);
        this.mc.player.swingHand(Hand.MAIN_HAND);

        if (stack.getItem() instanceof BlockItem block) {
            this.sound(block, hit.getBlockPos());
        }
    }

    private void sound(BlockItem block, BlockPos pos) {
        BlockSoundGroup sound = block.getBlock().getDefaultState().getSoundGroup();

        this.mc.world.playSound(pos.getX() + 0.5, pos.getY() + 0.5,
            pos.getZ() + 0.5, sound.getPlaceSound(), SoundCategory.BLOCKS,
            (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F, false
        );
    }

    private boolean valid(ItemStack stack) {
        return stack.getItem() instanceof BlockItem
            || stack.getItem() instanceof SpawnEggItem;
    }
}
