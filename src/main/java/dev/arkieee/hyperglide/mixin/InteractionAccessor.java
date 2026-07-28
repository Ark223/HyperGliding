package dev.arkieee.hyperglide.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.network.SequencedPacketCreator;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientPlayerInteractionManager.class)
public interface InteractionAccessor {
    @Invoker("sendSequencedPacket")
    void hyperglide$sendSequencedPacket(
        ClientWorld world, SequencedPacketCreator creator
    );
}
