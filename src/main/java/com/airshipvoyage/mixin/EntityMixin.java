package com.airshipvoyage.mixin;

import com.airshipvoyage.collision.ShipCollisions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Столкновения сущностей с деталями кораблей (ТЗ, 13.1): после обычной обработки блоков смещение
 * дополнительно обрезается кораблями. Флаги «стоит на земле», «упёрся в стену» и урон от падения
 * Minecraft затем вычисляет сам — по разнице между желаемым и разрешённым смещением.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "collide", at = @At("RETURN"), cancellable = true)
    private void airshipvoyage$collideWithShips(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        Vec3 clipped = ShipCollisions.collide((Entity) (Object) this, cir.getReturnValue());
        if (clipped != cir.getReturnValue()) cir.setReturnValue(clipped);
    }
}
