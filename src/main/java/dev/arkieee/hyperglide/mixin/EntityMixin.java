package dev.arkieee.hyperglide.mixin;

import dev.arkieee.hyperglide.modules.ControlFly;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "changeLookDirection(DD)V", at = @At("HEAD"), cancellable = true)
    private void hyperglide$changeLookDirection(double x, double y, CallbackInfo info) {
        MinecraftClient client = MinecraftClient.getInstance();
        if ((Object) this != client.player) return;

        ControlFly module = Modules.get().get(ControlFly.class);
        if (module == null || !module.view()) return;

        module.look(x, y);
        info.cancel();
    }
}
