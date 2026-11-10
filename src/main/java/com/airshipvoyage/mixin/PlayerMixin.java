package com.airshipvoyage.mixin;

import com.airshipvoyage.collision.ShipCollisions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ходьба с Shift по деталям корабля (ТЗ 12.2; ревью 2, №4; наблюдение владельца).
 *
 * <p>{@code Player.maybeBackOffFromEdge} до обработки столкновений проверяет, есть ли под ногами опора,
 * через {@code canFallAtLeast} → {@code Level.noCollision} — только блоки. Палуба корабля — не блок, поэтому
 * над пустотой с Shift нельзя было сделать ни шага, а у края палубы присевший не останавливался.
 * Здесь та же проба (коробка от ступней вниз на высоту ступеньки) дополнительно проверяется деталями кораблей.
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
    @Inject(method = "canFallAtLeast", at = @At("RETURN"), cancellable = true)
    private void airshipvoyage$shipPartsAreSupport(double x, double z, float distance, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        Player self = (Player) (Object) this;
        AABB bb = self.getBoundingBox();
        // та же коробка, что и в ванильном canFallAtLeast
        AABB probe = new AABB(bb.minX + x, bb.minY - (double) distance - 1.0E-5F, bb.minZ + z,
                bb.maxX + x, bb.minY, bb.maxZ + z);
        if (ShipCollisions.supports(self, probe)) cir.setReturnValue(false);
    }
}
