package dev.arkieee.hyperglide.mixin;

import dev.arkieee.hyperglide.modules.ControlFly;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireworkRocketEntity.class)
public abstract class FireworkMixin {
    @Shadow
    private LivingEntity shooter;

    @Inject(method = "tick", at = @At("HEAD"))
    private void hyperglide$track(CallbackInfo info) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || this.shooter != client.player) {
            return;
        }

        ControlFly module = Modules.get().get(ControlFly.class);
        if (module == null || !module.isActive()) return;

        module.track((FireworkRocketEntity) (Object) this);
    }
}
