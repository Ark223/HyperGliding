package dev.arkieee.hyperglide.mixin;

import dev.arkieee.hyperglide.modules.Overview;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DrawContext.class)
public abstract class ItemOverlayMixin {
    @Inject(
        method = "drawItem(Lnet/minecraft/entity/LivingEntity;" +
            "Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;" +
            "IIII)V",
        at = @At("TAIL")
    )
    private void onDrawItem(LivingEntity entity, World world, ItemStack
        stack, int x, int y, int seed, int z, CallbackInfo info) {
        Overview module = Modules.get().get(Overview.class);

        if (module != null) {
            module.render((DrawContext) (Object) this, stack, x, y);
        }
    }
}
