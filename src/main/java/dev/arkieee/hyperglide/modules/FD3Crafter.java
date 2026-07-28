package dev.arkieee.hyperglide.modules;

import dev.arkieee.hyperglide.Hyperglide;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

public class FD3Crafter extends Module {
    private static final int output = PlayerScreenHandler.CRAFTING_RESULT_ID;
    private static final int paper = PlayerScreenHandler.CRAFTING_INPUT_START;
    private static final int first = paper + 1;
    private static final int second = paper + 2;
    private static final int third = paper + 3;

    private final SettingGroup general = this.settings.getDefaultGroup();

    private final Setting<Integer> delay = this.general.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Ticks to wait between inventory actions.")
        .defaultValue(3)
        .min(1)
        .sliderMax(5)
        .build()
    );

    private Phase phase;
    private int source;
    private int target;
    private int tick;
    private int oldpaper;
    private int oldpowder;
    private int start;
    private int idle;
    private boolean bulk;
    private boolean close;
    private String note;

    private enum Phase {
        CLEAR,
        WORK,
        PAPER,
        STACK,
        POWDER,
        RETURN,
        MERGE,
        CHECK,
        CLEAN
    }

    public FD3Crafter() {
        super(Hyperglide.CATEGORY, "fd3-crafter",
            "Strictly crafts Fd3 rockets using gunpowder and paper."
        );
    }

    @Override
    public void onActivate() {
        if (this.mc.player == null || this.mc.world == null ||
            this.mc.interactionManager == null) {
            this.toggle();
            return;
        }

        if (this.mc.player.isCreative()) {
            this.error("Crafter does not support creative mode.");
            this.toggle();
            return;
        }

        if (this.mc.player.currentScreenHandler !=
            this.mc.player.playerScreenHandler) {
            this.mc.player.closeHandledScreen();
        }

        this.phase = Phase.CLEAR;
        this.source = -1;
        this.target = -1;
        this.tick = 0;

        this.oldpaper = 0;
        this.oldpowder = 0;
        this.start = this.rockets();
        this.idle = 0;

        this.bulk = false;
        this.close = false;
        this.note = null;

        this.mc.setScreen(new InventoryScreen(this.mc.player));
    }

    @Override
    public void onDeactivate() {
        if (this.close && this.mc.currentScreen instanceof InventoryScreen) {
            this.mc.setScreen(null);
        }

        this.phase = null;

        this.source = -1;
        this.target = -1;
        this.tick = 0;
        this.idle = 0;

        this.bulk = false;
        this.close = false;
        this.note = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (this.mc.player == null || this.mc.world == null
            || this.mc.interactionManager == null) return;

        if (!(this.mc.currentScreen instanceof InventoryScreen)
            || this.mc.player.currentScreenHandler !=
                this.mc.player.playerScreenHandler) {
            this.error("Inventory was closed.");
            this.toggle();
            return;
        }

        if (this.tick < this.delay.get()) {
            this.tick++;
            return;
        }

        this.tick = 0;
        this.step();
    }

    private void step() {
        switch (this.phase) {
            case CLEAR -> this.clear();
            case WORK -> this.work();
            case PAPER -> this.paper();
            case STACK -> this.stack();
            case POWDER -> this.powder();
            case RETURN -> this.back();
            case MERGE -> this.merge();
            case CHECK -> this.check();
            case CLEAN -> this.clean();
        }
    }

    private void clear() {
        if (!this.handler().getCursorStack().isEmpty()) {
            this.error("Clear the cursor before enabling crafter.");
            this.toggle();
            return;
        }

        for (int idx = paper; idx <= third; idx++) {
            if (!this.stack(idx).isEmpty()) {
                this.click(idx, 0, SlotActionType.QUICK_MOVE);
                return;
            }
        }

        this.phase = Phase.WORK;
    }

    private void work() {
        int one = this.stack(first).getCount();
        int two = this.stack(second).getCount();
        int three = this.stack(third).getCount();

        if (!this.same(first, Items.GUNPOWDER) ||
            !this.same(second, Items.GUNPOWDER) ||
            !this.same(third, Items.GUNPOWDER)) {
            this.fail("A gunpowder slot contains another item.");
            return;
        }

        if (this.stack(paper).isEmpty()) {
            this.source = this.largest(Items.PAPER, 1);

            if (this.source == -1) {
                this.phase = Phase.CLEAN;
                return;
            }

            this.click(this.source, 0, SlotActionType.PICKUP);
            this.phase = Phase.PAPER;
            return;
        } else if (!this.stack(paper).isOf(Items.PAPER)) {
            this.fail("The paper slot contains another item.");
            return;
        }

        if (one == two && two == three && one > 0) {
            ItemStack stack = this.stack(output);

            if (stack.isEmpty()) {
                if (++this.idle > 5) {
                    this.fail("Fd3 rocket recipe did not appear.");
                }
                return;
            }

            this.idle = 0;

            if (!this.rocket(stack)) {
                this.fail("Crafting output was not Fd3 rocket.");
                return;
            }

            this.oldpaper = this.stack(paper).getCount();
            this.oldpowder = one;

            this.click(output, 0, SlotActionType.QUICK_MOVE);
            this.phase = Phase.CHECK;
            return;
        }

        if (this.bulk) {
            if ((one != 0 && one != 64) ||
                (two != 0 && two != 64) ||
                (three != 0 && three != 64)) {
                this.fail("A full gunpowder stack changed size.");
                return;
            }

            this.target = this.powderslot();

            if (this.target == -1) {
                this.fail("Full gunpowder stacks became uneven.");
                return;
            }

            this.source = this.full(Items.GUNPOWDER);

            if (this.source == -1) {
                this.fail("A full gunpowder stack is missing.");
                return;
            }

            this.click(this.source, 0, SlotActionType.PICKUP);
            this.phase = Phase.STACK;
            return;
        }

        if (one != 0 || two != 0 || three != 0) {
            this.fail("Gunpowder slots became uneven.");
            return;
        }

        if (this.fulls(Items.GUNPOWDER) >= 3) {
            this.bulk = true;
            this.target = first;
            this.source = this.full(Items.GUNPOWDER);

            this.click(this.source, 0, SlotActionType.PICKUP);
            this.phase = Phase.STACK;
            return;
        }

        this.source = this.largest(Items.GUNPOWDER, 3);

        if (this.source != -1) {
            this.click(this.source, 0, SlotActionType.PICKUP);
            this.phase = Phase.POWDER;
            return;
        }

        if (this.count(Items.GUNPOWDER) >= 3) {
            int[] pair = this.pair();

            if (pair != null) {
                this.source = pair[0];
                this.target = pair[1];

                this.click(this.source, 0, SlotActionType.PICKUP);
                this.phase = Phase.MERGE;
                return;
            }
        }

        this.phase = Phase.CLEAN;
    }

    private void paper() {
        if (!this.handler().getCursorStack().isOf(Items.PAPER)) {
            this.fail("Paper pickup failed.");
            return;
        }

        this.click(paper, 0, SlotActionType.PICKUP);
        this.phase = Phase.WORK;
    }

    private void stack() {
        if (!this.handler().getCursorStack().isOf(Items.GUNPOWDER) ||
            this.handler().getCursorStack().getCount() != 64) {
            this.fail("Full gunpowder pickup failed.");
            return;
        }

        this.click(this.target, 0, SlotActionType.PICKUP);
        this.phase = Phase.WORK;
    }

    private void powder() {
        if (!this.handler().getCursorStack().isOf(Items.GUNPOWDER)) {
            this.fail("Gunpowder pickup failed.");
            return;
        }

        int sync = this.handler().syncId;

        this.mc.interactionManager.clickSlot(sync,
            ScreenHandler.EMPTY_SPACE_SLOT_INDEX,
            ScreenHandler.packQuickCraftData(0, 0),
            SlotActionType.QUICK_CRAFT, this.mc.player
        );

        this.mc.interactionManager.clickSlot(sync, first,
            ScreenHandler.packQuickCraftData(1, 0),
            SlotActionType.QUICK_CRAFT, this.mc.player
        );

        this.mc.interactionManager.clickSlot(sync, second,
            ScreenHandler.packQuickCraftData(1, 0),
            SlotActionType.QUICK_CRAFT, this.mc.player
        );

        this.mc.interactionManager.clickSlot(sync, third,
            ScreenHandler.packQuickCraftData(1, 0),
            SlotActionType.QUICK_CRAFT, this.mc.player
        );

        this.mc.interactionManager.clickSlot(sync,
            ScreenHandler.EMPTY_SPACE_SLOT_INDEX,
            ScreenHandler.packQuickCraftData(2, 0),
            SlotActionType.QUICK_CRAFT, this.mc.player
        );

        this.phase = Phase.RETURN;
    }

    private void back() {
        if (this.handler().getCursorStack().isEmpty()) {
            this.phase = Phase.WORK;
            return;
        }

        this.click(this.source, 0, SlotActionType.PICKUP);
        this.phase = Phase.WORK;
    }

    private void merge() {
        if (!this.handler().getCursorStack().isOf(Items.GUNPOWDER)) {
            this.fail("Gunpowder merge failed.");
            return;
        }

        this.click(this.target, 0, SlotActionType.PICKUP);
        this.phase = Phase.WORK;
    }

    private void check() {
        int paper = this.stack(FD3Crafter.paper).getCount();
        int powder = this.stack(first).getCount();

        if (paper < this.oldpaper || powder < this.oldpowder) {
            this.idle = 0;
            this.bulk = false;
            this.phase = Phase.WORK;
            this.tick = this.delay.get();
            return;
        }

        if (++this.idle > 3) {
            this.fail("Not enough space for rockets.");
        }
    }

    private void clean() {
        if (!this.handler().getCursorStack().isEmpty()) {
            int slot = this.empty();

            if (slot == -1) {
                this.error("Not enough space for cursor stack.");
                this.toggle();
                return;
            }

            this.click(slot, 0, SlotActionType.PICKUP);
            return;
        }

        for (int idx = paper; idx <= third; idx++) {
            if (!this.stack(idx).isEmpty()) {
                this.click(idx, 0, SlotActionType.QUICK_MOVE);
                return;
            }
        }

        this.done();
    }

    private void fail(String note) {
        this.note = note;
        this.phase = Phase.CLEAN;
    }

    private void done() {
        int made = Math.max(0, this.rockets() - this.start);
        if (this.note != null) this.error(this.note);

        if (made > 0) {
            this.info("Successfully made " + made + " rockets.");
        } else if (this.note == null) {
            this.error("Need more ingredients for crafting.");
        }

        this.close = true;
        this.toggle();
    }

    private void click(int slot, int button, SlotActionType action) {
        this.mc.interactionManager.clickSlot(this.handler().syncId,
            slot, button, action, this.mc.player);
    }

    private PlayerScreenHandler handler() {
        return this.mc.player.playerScreenHandler;
    }

    private ItemStack stack(int slot) {
        return this.handler().getSlot(slot).getStack();
    }

    private boolean same(int slot, Item item) {
        ItemStack stack = this.stack(slot);
        return stack.isEmpty() || stack.isOf(item);
    }

    private int largest(Item item, int min) {
        int slot = -1;
        int count = min - 1;
        for (int idx = PlayerScreenHandler.INVENTORY_START;
            idx < PlayerScreenHandler.HOTBAR_END; idx++) {
            ItemStack stack = this.stack(idx);

            if (stack.isOf(item) && stack.getCount() > count) {
                slot = idx;
                count = stack.getCount();
            }
        }
        return slot;
    }

    private int full(Item item) {
        for (int idx = PlayerScreenHandler.INVENTORY_START;
            idx < PlayerScreenHandler.HOTBAR_END; idx++) {
            ItemStack stack = this.stack(idx);

            if (stack.isOf(item) &&
                stack.getCount() == stack.getMaxCount()) {
                return idx;
            }
        }
        return -1;
    }

    private int fulls(Item item) {
        int count = 0;
        for (int idx = PlayerScreenHandler.INVENTORY_START;
            idx < PlayerScreenHandler.HOTBAR_END; idx++) {
            ItemStack stack = this.stack(idx);

            if (stack.isOf(item) &&
                stack.getCount() == stack.getMaxCount()) {
                count++;
            }
        }
        return count;
    }

    private int powderslot() {
        if (this.stack(first).isEmpty()) return first;
        if (this.stack(second).isEmpty()) return second;
        if (this.stack(third).isEmpty()) return third;
        return -1;
    }

    private int count(Item item) {
        int count = 0;
        for (int idx = PlayerScreenHandler.INVENTORY_START;
            idx < PlayerScreenHandler.HOTBAR_END; idx++) {
            ItemStack stack = this.stack(idx);
            if (stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }

    private int[] pair() {
        int first = -1;
        for (int idx = PlayerScreenHandler.INVENTORY_START;
            idx < PlayerScreenHandler.HOTBAR_END; idx++) {
            ItemStack stack = this.stack(idx);

            if (!stack.isOf(Items.GUNPOWDER) ||
                stack.getCount() >= 3) continue;

            if (first == -1) {
                first = idx;
            } else {
                return new int[] { idx, first };
            }
        }
        return null;
    }

    private int empty() {
        for (int idx = PlayerScreenHandler.INVENTORY_START;
            idx < PlayerScreenHandler.HOTBAR_END; idx++) {
            if (this.stack(idx).isEmpty()) return idx;
        }
        return -1;
    }

    private boolean rocket(ItemStack stack) {
        if (!stack.isOf(Items.FIREWORK_ROCKET)) return false;

        FireworksComponent component = stack.get(DataComponentTypes.FIREWORKS);
        return component != null && component.flightDuration() == 3;
    }

    private int rockets() {
        int count = 0;
        for (int idx = PlayerScreenHandler.INVENTORY_START;
            idx < PlayerScreenHandler.HOTBAR_END; idx++) {

            ItemStack stack = this.stack(idx);
            if (this.rocket(stack)) count += stack.getCount();
        }
        return count;
    }
}
