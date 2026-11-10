package com.airshipvoyage.debug;

import com.airshipvoyage.AirshipVoyage;
import com.airshipvoyage.core.assembly.AssemblyPlans;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.physics.GasFilling;
import com.airshipvoyage.core.structure.PrototypeShip;
import com.airshipvoyage.gas.GasSupplyItem;
import com.airshipvoyage.gas.GasSupplyService;
import com.airshipvoyage.physics.AirshipEntity;
import com.airshipvoyage.registry.ModEntities;
import com.airshipvoyage.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Заправка настоящими предметами и сохранение газа (ТЗ 7.3, 22.9); ввод мыши проверяется владельцем. */
@GameTestHolder(AirshipVoyage.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GasSupplyGameTests {
    private static AirshipEntity ship(GameTestHelper h, Quat rotation) {
        var ship = new AirshipEntity(ModEntities.AIRSHIP.get(), h.getLevel());
        ship.setLayoutById(PrototypeShip.LAYOUT_ID);
        var pos = h.absoluteVec(new Vec3(14, 12, 14));
        ship.placeAt(new Vec3d(pos.x, pos.y, pos.z), rotation);
        ship.flight().setNeutral(true);
        h.getLevel().addFreshEntity(ship);
        return ship;
    }

    private static Player aim(GameTestHelper h, AirshipEntity ship, int cell, double offsetZ) {
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        var port = ship.layout().cells().get(cell).fillingPort().center();
        var feet = ship.body().localToWorld(new Vec3d(port.x(), PrototypeShip.DECK_TOP, offsetZ));
        player.setPos(feet.x(), feet.y(), feet.z());
        var target = ship.body().localToWorld(port);
        var eye = player.getEyePosition();
        double x = target.x() - eye.x, y = target.y() - eye.y, z = target.z() - eye.z;
        player.setYRot((float) Math.toDegrees(Math.atan2(-x, z)));
        player.setYHeadRot(player.getYRot()); // LivingEntity.getViewYRot читает поворот головы, не тела.
        player.setXRot((float) -Math.toDegrees(Math.atan2(y, Math.hypot(x, z))));
        return player;
    }

    @GameTest(template = "ship_dock", batch = "gas_supply", timeoutTicks = 100)
    public static void heldSupplyConservesGasAndStopsOnRelease(GameTestHelper h) {
        var ship = ship(h, Quat.IDENTITY);
        var player = aim(h, ship, 0, 0.8);
        var item = ModItems.HYDROGEN_SUPPLY.get();
        var stack = new ItemStack(item);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        item.use(h.getLevel(), player, InteractionHand.MAIN_HAND);
        h.assertTrue(player.isUsingItem(), "supply did not start");
        double initialMass = ship.body().massProperties().massKg();
        for (int i = 0; i < 20; i++) item.onUseTick(h.getLevel(), player, stack, 72000 - i);
        var cell = ship.flight().cells().get(0);
        h.assertTrue(Math.abs(cell.amountMol() - 422.925433799) < 1e-6, "one second: " + cell.amountMol());
        h.assertTrue(ship.flight().cells().get(1).amountMol() == 0, "other cell filled");
        h.assertTrue(Math.abs(cell.amountMol() + GasSupplyItem.remainingMol(stack) - GasFilling.SUPPLY_MOL) < 1e-6,
                "supply does not conserve gas");
        h.assertTrue(Math.abs(ship.body().massProperties().massKg() - initialMass - 0.8526176745) < 1e-6,
                "gas mass not applied");
        var saved = (CompoundTag) stack.save(h.getLevel().registryAccess());
        var copy = ItemStack.parseOptional(h.getLevel().registryAccess(), saved);
        h.assertTrue(GasSupplyItem.remainingMol(copy) == GasSupplyItem.remainingMol(stack), "reserve not saved");
        player.stopUsingItem();
        double before = cell.amountMol();
        item.onUseTick(h.getLevel(), player, stack, 71979);
        h.assertTrue(cell.amountMol() == before, "supply continued after release");
        AirshipVoyage.LOGGER.info("[test] gas supply: {} mol, gas mass {} kg, reserve {} mol",
                cell.amountMol(), cell.gasMassKg(), GasSupplyItem.remainingMol(stack));
        h.succeed();
    }

    /**
     * Ревью Claude кода Codex: корабль качается, а штуцер 30 см — подача не должна обрываться, если взгляд
     * ушёл со штуцера. Подключились прицелом — отвернулись на 90° — газ идёт; отошли дальше досягаемости
     * (4,5 + 1 м слабины) — подача остановлена. Без «шланга» обрывалось на первом же тике.
     */
    @GameTest(template = "ship_dock", batch = "gas_supply", timeoutTicks = 100)
    public static void hoseKeepsFillingWhenAimDriftsAndStopsWhenFar(GameTestHelper h) {
        var ship = ship(h, Quat.IDENTITY);
        var player = aim(h, ship, 0, 0.8);
        var item = ModItems.HYDROGEN_SUPPLY.get();
        var stack = new ItemStack(item);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        item.use(h.getLevel(), player, InteractionHand.MAIN_HAND);
        h.assertTrue(player.isUsingItem(), "supply did not start");
        player.setYRot(player.getYRot() + 90);
        player.setYHeadRot(player.getYRot());
        player.setXRot(0);
        h.assertTrue(GasSupplyService.findTarget(player).isEmpty(), "port still under the crosshair");
        for (int i = 0; i < 10; i++) item.onUseTick(h.getLevel(), player, stack, 72000 - i);
        var cell = ship.flight().cells().get(0);
        h.assertTrue(player.isUsingItem() && Math.abs(cell.amountMol() - 422.925433799 / 2) < 1e-6,
                "hose dropped when the aim drifted: " + cell.amountMol());
        var away = ship.body().localToWorld(new Vec3d(ship.layout().cells().get(0).fillingPort().center().x() - 8,
                PrototypeShip.DECK_TOP, 0));
        player.setPos(away.x(), away.y(), away.z());
        double before = cell.amountMol();
        item.onUseTick(h.getLevel(), player, stack, 71990);
        h.assertTrue(!player.isUsingItem() && cell.amountMol() == before, "hose stretched beyond reach");
        h.succeed();
    }

    @GameTest(template = "ship_dock", batch = "gas_supply", timeoutTicks = 100)
    public static void tiltedPortRejectsBlockedAndDistantSupply(GameTestHelper h) {
        var ship = ship(h, Quat.fromYawPitchRoll(Math.toRadians(12), Math.toRadians(0.4), Math.toRadians(0.3)));
        var player = aim(h, ship, 1, 2.5);
        h.assertTrue(GasSupplyService.findTarget(player).map(t -> t.ship() == ship && t.cell() == 1).orElse(false),
                "tilted port not targeted");
        var port = ship.body().localToWorld(ship.layout().cells().get(1).fillingPort().center());
        var eye = player.getEyePosition();
        var block = BlockPos.containing((eye.x + port.x()) / 2, (eye.y + port.y()) / 2, (eye.z + port.z()) / 2);
        h.getLevel().setBlock(block, Blocks.STONE.defaultBlockState(), 3);
        h.assertTrue(GasSupplyService.findTarget(player).isEmpty(), "port targeted through a block");
        var item = ModItems.HYDROGEN_SUPPLY.get();
        var stack = new ItemStack(item);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        player.startUsingItem(InteractionHand.MAIN_HAND);
        item.onUseTick(h.getLevel(), player, stack, 72000);
        h.assertTrue(!player.isUsingItem() && ship.flight().cells().get(1).amountMol() == 0, "blocked transfer");
        var far = aim(h, ship, 1, 12);
        h.assertTrue(GasSupplyService.findTarget(far).isEmpty(), "port targeted beyond reach");
        h.assertTrue(GasSupplyItem.remainingMol(stack) == GasFilling.SUPPLY_MOL, "refused supply consumed gas");
        h.succeed();
    }

    @GameTest(template = "ship_dock", batch = "gas_supply", timeoutTicks = 100)
    public static void gasAndValvesSurviveConstructionSaveAndLaunch(GameTestHelper h) {
        var ship = ship(h, Quat.IDENTITY);
        ship.startConstruction(AssemblyPlans.prototype());
        var player = aim(h, ship, 0, 0.8);
        h.assertTrue(GasSupplyService.findTarget(player).isEmpty(), "missing cell has a port");
        for (var step : AssemblyPlans.prototype().steps()) ship.construction().install(step.slot(), step.itemKey());
        ship.constructionChanged();
        var item = ModItems.HELIUM_SUPPLY.get();
        var stack = new ItemStack(item);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        item.use(h.getLevel(), player, InteractionHand.MAIN_HAND);
        item.onUseTick(h.getLevel(), player, stack, 72000);
        player.stopUsingItem();
        var cell = ship.flight().cells().get(0);
        h.assertTrue(cell.amountMol() > 0, "gas not supplied on slipway");
        double amount = cell.amountMol();
        cell.setLeakPerSecond(0.02);
        player.setShiftKeyDown(true);
        item.use(h.getLevel(), player, InteractionHand.MAIN_HAND);
        h.assertTrue(cell.manualValveOpen(), "individual valve not opened");
        h.assertTrue(!ship.flight().cells().get(1).manualValveOpen(), "other valve opened");
        CompoundTag saved = ship.saveWithoutId(new CompoundTag());
        var copy = new AirshipEntity(ModEntities.AIRSHIP.get(), h.getLevel());
        copy.load(saved);
        var restored = copy.flight().cells().get(0);
        h.assertTrue(copy.isUnderConstruction() && copy.construction().complete(), "construction lost");
        h.assertTrue(restored.amountMol() == amount && restored.gas() == item.gas() && restored.manualValveOpen(),
                "gas/type/valve lost on reload");
        copy.launch();
        h.assertTrue(!copy.isUnderConstruction() && restored.amountMol() == amount && restored.leakPerSecond() == 0.02,
                "launch discarded supplied gas or repaired leakage");
        int start = ship.tickCount;
        h.onEachTick(() -> {
            int ticks = ship.tickCount - start;
            if (ticks < 5) return;
            // Утечка 2 %/с и клапан 1 %/с: n(t) = n0 exp(−0,03t); корпус на стапеле неподвижен.
            double expected = amount * Math.exp(-0.03 * ticks * 0.05);
            h.assertTrue(Math.abs(cell.amountMol() - expected) < 1e-7, "slipway gas tick: " + cell.amountMol());
            h.assertTrue(ship.body().velocity().length() == 0, "slipway moved");
            h.assertTrue(ship.cellFillForRender()[0] > 0, "slipway fill not synced");
            h.succeed();
        });
    }

    @GameTest(template = "ship_dock", batch = "gas_supply", timeoutTicks = 100)
    public static void exhaustedSupplyAndDifferentGasCannotRefill(GameTestHelper h) {
        var ship = ship(h, Quat.IDENTITY);
        var player = aim(h, ship, 0, 0.8);
        var item = ModItems.HYDROGEN_SUPPLY.get();
        var stack = new ItemStack(item);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        item.use(h.getLevel(), player, InteractionHand.MAIN_HAND);
        for (int i = 0; i < 402; i++) item.onUseTick(h.getLevel(), player, stack, 72000 - i);
        h.assertTrue(GasSupplyItem.remainingMol(stack) < 1e-8 && !player.isUsingItem(), "supply not exhausted");
        double before = ship.flight().cells().get(0).amountMol();
        var copy = ItemStack.parseOptional(h.getLevel().registryAccess(), (CompoundTag) stack.save(h.getLevel().registryAccess()));
        h.assertTrue(GasSupplyItem.remainingMol(copy) < 1e-8, "empty reserve reset after load");
        var helium = new ItemStack(ModItems.HELIUM_SUPPLY.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, helium);
        ModItems.HELIUM_SUPPLY.get().use(h.getLevel(), player, InteractionHand.MAIN_HAND);
        ModItems.HELIUM_SUPPLY.get().onUseTick(h.getLevel(), player, helium, 72000);
        h.assertTrue(!player.isUsingItem() && ship.flight().cells().get(0).amountMol() == before, "mixed gases");
        h.assertTrue(GasSupplyItem.remainingMol(helium) == GasFilling.SUPPLY_MOL, "incompatible supply consumed gas");
        h.succeed();
    }

    private GasSupplyGameTests() {}

    @GameTest(template = "ship_dock", batch = "gas_supply", timeoutTicks = 100)
    public static void valveChangesOncePerPressAndNeverStartsFillingOnShiftRelease(GameTestHelper h) {
        var ship = ship(h, Quat.IDENTITY);
        var player = aim(h, ship, 0, 0.8);
        var item = ModItems.HYDROGEN_SUPPLY.get();
        var stack = new ItemStack(item);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        player.setShiftKeyDown(true);
        item.use(h.getLevel(), player, InteractionHand.MAIN_HAND);
        var cell = ship.flight().cells().get(0);
        h.assertTrue(cell.manualValveOpen(), "first press did not open valve");
        for (int i = 0; i < 20; i++) {
            item.use(h.getLevel(), player, InteractionHand.MAIN_HAND);
            item.onUseTick(h.getLevel(), player, stack, 72000 - i);
            h.assertTrue(cell.manualValveOpen(), "held press toggled valve again");
        }
        player.setShiftKeyDown(false);
        item.onUseTick(h.getLevel(), player, stack, 71979);
        h.assertTrue(cell.amountMol() == 0, "releasing Shift started filling");
        player.stopUsingItem();
        player.setShiftKeyDown(true);
        item.use(h.getLevel(), player, InteractionHand.MAIN_HAND);
        h.assertTrue(!cell.manualValveOpen(), "new press did not close valve");
        player.stopUsingItem();
        h.succeed();
    }
}
