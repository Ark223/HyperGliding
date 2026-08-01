package dev.arkieee.hyperglide.mixin;

import dev.arkieee.hyperglide.modules.BounceFly;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(CallbackInfo info) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!(client.currentScreen instanceof HandledScreen<?>) &&
            !(client.currentScreen instanceof WidgetScreen)) {
            return;
        }

        BounceFly module = Modules.get().get(BounceFly.class);
        if (module != null) module.input((Input) (Object) this);
    }
}
