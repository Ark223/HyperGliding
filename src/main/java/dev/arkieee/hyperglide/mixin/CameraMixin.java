package dev.arkieee.hyperglide.mixin;

import dev.arkieee.hyperglide.modules.ControlFly;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @ModifyArgs(method = "update", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/render/Camera;setRotation(FF)V"
    ))
    private void hyperglide$rotate(Args args) {
        ControlFly module = Modules.get().get(ControlFly.class);
        if (module == null || !module.camera()) return;

        args.set(0, module.yaw());
        args.set(1, module.pitch());
    }
}
