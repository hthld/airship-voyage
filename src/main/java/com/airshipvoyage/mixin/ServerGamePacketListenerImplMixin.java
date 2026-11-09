package com.airshipvoyage.mixin;

import com.airshipvoyage.collision.ShipCollisions;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Защита от полёта на выделенном сервере выкидывает игрока, который несколько секунд «висит в воздухе»
 * без блоков вокруг. Палуба корабля — не блок, поэтому стоящий на ней игрок считается рядом с опорой.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Inject(method = "noBlocksAround", at = @At("RETURN"), cancellable = true)
    private void airshipvoyage$shipCountsAsGround(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && ShipCollisions.isNearHull(entity)) cir.setReturnValue(false);
    }
}
