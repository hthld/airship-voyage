package com.airshipvoyage.debug;

import com.airshipvoyage.AirshipVoyage;
import com.airshipvoyage.control.FuelCanisterItem;
import com.airshipvoyage.control.HelmService;
import com.airshipvoyage.control.MooringMastBlock;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.propulsion.Engine;
import com.airshipvoyage.core.structure.PrototypeShip;
import com.airshipvoyage.physics.AirshipEntity;
import com.airshipvoyage.registry.ModBlocks;
import com.airshipvoyage.registry.ModEntities;
import com.airshipvoyage.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Фаза 7 «Эксплуатация» в игре (ТЗ 8.4, 9.3, 10.2): канистра, автопилот с пульта, причальная мачта, сохранение. */
@GameTestHolder(AirshipVoyage.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OpsGameTests {
    private static final String DOCK = "ship_dock";

    private static AirshipEntity flyingShip(GameTestHelper h, double relY) {
        Vec3 p = h.absoluteVec(new Vec3(14, relY, 14));
        AirshipEntity ship = new AirshipEntity(ModEntities.AIRSHIP.get(), h.getLevel());
        ship.setLayoutById(PrototypeShip.LAYOUT_ID);
        ship.placeAt(new Vec3d(p.x, p.y, p.z), Quat.IDENTITY);
        ship.flight().weighOff(ship.environment());
        h.getLevel().addFreshEntity(ship);
        return ship;
    }

    /**
     * Канистра (10 кг): в левый бак, где осталось 3 кг, входит 7 — в канистре остаётся 3; корабль тяжелее на 7 кг.
     * Топливо, автопилот и трос переживают сохранение мира.
     */
    @GameTest(template = DOCK, batch = "ops")
    public static void canisterRefuelsAndStateSurvivesSave(GameTestHelper h) {
        AirshipEntity ship = flyingShip(h, 12);
        var port = ship.flight().engine("engine_port");
        port.setFuelKg(3);
        ship.flight().massChanged();
        double mass = ship.body().massKg();
        ItemStack can = new ItemStack(ModItems.FUEL_CANISTER.get());
        double in = FuelCanisterItem.pour(ship, "engine_port", can, false);
        h.assertTrue(Math.abs(in - 7) < 1e-9 && Math.abs(FuelCanisterItem.fuelKg(can) - 3) < 1e-9, "poured " + in);
        h.assertTrue(Math.abs(ship.body().massKg() - mass - 7) < 1e-6, "mass " + ship.body().massKg());
        // в «самый пустой бак»: по любой другой детали
        ship.flight().engine("engine_stbd").setFuelKg(9);
        port.setFuelKg(8);
        FuelCanisterItem.pour(ship, "keel", can, false);
        h.assertTrue(Math.abs(port.fuelKg() - 10) < 1e-9, "emptiest tank not chosen: " + port.fuelKg());

        port.setFuelKg(4.25);
        ship.flight().massChanged();
        ship.flight().engageAutopilot(ship.environment());
        ship.flight().autopilot().setHeading(33);
        Vec3d anchor = ship.flight().body().localToWorld(ship.flight().noseLocal()).add(new Vec3d(3, 0, 0));
        ship.moor(anchor);
        CompoundTag tag = new CompoundTag();
        ship.saveWithoutId(tag);
        AirshipEntity copy = new AirshipEntity(ModEntities.AIRSHIP.get(), h.getLevel());
        copy.load(tag);
        h.assertTrue(Math.abs(copy.flight().engine("engine_port").fuelKg() - 4.25) < 1e-9, "fuel lost");
        h.assertTrue(copy.flight().autopilot().engaged() && Math.abs(copy.flight().autopilot().headingDeg() - 33) < 1e-9, "autopilot lost");
        h.assertTrue(copy.flight().mooringAnchor() != null && copy.flight().mooringAnchor().distanceTo(anchor) < 1e-9, "mooring lost");
        h.assertTrue(Math.abs(copy.body().massKg() - ship.body().massKg()) < 1e-6, "mass differs after load");
        ship.discard();
        h.succeed();
    }

    /** Пульт: P включает автопилот на нынешнем курсе; A при включённом крутит задатчик курса (0,5°/тик), а не руль. */
    @GameTest(template = DOCK, batch = "ops")
    public static void autopilotFromTheHelm(GameTestHelper h) {
        AirshipEntity ship = flyingShip(h, 12);
        var pilot = h.makeMockPlayer(GameType.SURVIVAL);
        Vec3d at = ship.body().localToWorld(new Vec3d(8.2, PrototypeShip.DECK_TOP, 0));
        pilot.setPos(at.x(), at.y(), at.z());
        h.assertTrue(HelmService.take(pilot, ship) == HelmService.Result.TAKEN, "take");
        HelmService.toggleAutopilot(ship);
        var ap = ship.flight().autopilot();
        h.assertTrue(ap.engaged() && Math.abs(ap.headingDeg()) < 1e-6, "not engaged at the current heading");
        for (int i = 0; i < 10; i++) HelmService.apply(pilot, ship, 0, HelmService.RUDDER_DEG, 0, false, false);
        h.assertTrue(Math.abs(ap.headingDeg() - 5) < 1e-9, "heading knob " + ap.headingDeg());
        h.assertTrue(ship.flight().rudderCommandDeg() == 0, "A moved the rudder instead of the knob");
        HelmService.toggleAutopilot(ship);
        h.assertTrue(!ap.engaged(), "not disengaged");
        ship.discard();
        h.succeed();
    }

    /**
     * Причальная мачта: голова мачты в 5 м перед носом (на блоках дока). ПКМ — трос заведён, лебёдка за 15 с
     * подтягивает нос ближе 3 м; повторный ПКМ — трос отдан; сломали голову — трос отдан.
     */
    @GameTest(template = DOCK, timeoutTicks = 500, batch = "ops")
    public static void mooringMastHoldsTheShip(GameTestHelper h) {
        AirshipEntity ship = flyingShip(h, 12);
        ship.flight().setNeutral(true); // вес не мешает — проверяется трос
        Vec3d nose = ship.flight().body().localToWorld(ship.flight().noseLocal());
        BlockPos mast = BlockPos.containing(nose.x() + 3.5, nose.y() - 1, nose.z());
        h.getLevel().setBlockAndUpdate(mast, ModBlocks.MOORING_MAST.get().defaultBlockState());
        h.runAfterDelay(2, () -> {
            MooringMastBlock.toggle(h.getLevel(), mast);
            h.assertTrue(ship.flight().mooringAnchor() != null, "not moored");
            h.assertTrue(ship.mooringAnchorForRender() != null, "rope not synced");
            h.runAfterDelay(300, () -> {
                double d = ship.flight().body().localToWorld(ship.flight().noseLocal()).distanceTo(MooringMastBlock.anchor(mast));
                AirshipVoyage.LOGGER.info("[test] moored: rope {} m, nose {} m from the mast head", ship.flight().ropeLength(),
                        String.format("%.2f", d));
                h.assertTrue(d < 3.0, "winch did not pull the nose in: " + d);
                MooringMastBlock.toggle(h.getLevel(), mast);
                h.assertTrue(ship.flight().mooringAnchor() == null, "not released");
                MooringMastBlock.toggle(h.getLevel(), mast);
                h.getLevel().destroyBlock(mast, false);
                h.assertTrue(ship.flight().mooringAnchor() == null, "rope kept after the mast head was broken");
                ship.discard();
                h.succeed();
            });
        });
    }

    /** Корабль над полом дока: низ колец (y = −6) в 1,5 м над полом. Нейтрально — падает только с заданной скоростью. */
    private static AirshipEntity shipAboveFloor(GameTestHelper h, double vy) {
        Vec3 p = h.absoluteVec(new Vec3(14, 2 + 6 + 1.5, 14));
        AirshipEntity ship = new AirshipEntity(ModEntities.AIRSHIP.get(), h.getLevel());
        ship.setLayoutById(PrototypeShip.LAYOUT_ID);
        ship.placeAt(new Vec3d(p.x, p.y, p.z), Quat.IDENTITY);
        ship.flight().weighOff(ship.environment());
        ship.flight().setNeutral(true);
        ship.body().setVelocity(new Vec3d(0, vy, 0));
        h.getLevel().addFreshEntity(ship);
        return ship;
    }

    /**
     * Удар о землю (ТЗ 18, 20; AV-040): корабль врезался в пол дока на 5 м/с — урон 6·(5 − 3)² = 24: нижние фермы
     * (прочность 20) разлетелись, кольца (48,4) выдержали. На 2 м/с — мягкая посадка, ничего не сломано.
     */
    @GameTest(template = DOCK, timeoutTicks = 120, batch = "impact")
    public static void hardLandingBreaksLowerParts(GameTestHelper h) {
        AirshipEntity hard = shipAboveFloor(h, -5);
        h.runAfterDelay(30, () -> {
            var gone = hard.damage().destroyed();
            AirshipVoyage.LOGGER.info("[test] hard landing at 5 m/s: destroyed {}, ring_mid hp {}", gone,
                    String.format("%.1f", hard.damage().hp("ring_mid")));
            h.assertTrue(gone.contains("girder_bot_port") && gone.contains("girder_bot_stbd"), "bottom girders survived: " + gone);
            h.assertTrue(!gone.contains("ring_mid") && hard.damage().hp("ring_mid") < hard.damage().maxHp("ring_mid"), "ring state");
            hard.discard();
            AirshipEntity soft = shipAboveFloor(h, -2);
            h.runAfterDelay(50, () -> {
                h.assertTrue(soft.damage().destroyed().isEmpty() && soft.damage().damageTaken().isEmpty(),
                        "soft landing damaged " + soft.damage().damageTaken());
                soft.discard();
                h.succeed();
            });
        });
    }

    /** Ветер в мире (ТЗ 8.6): заданный ветер 3 м/с на юг сносит висящий корабль на юг. */
    @GameTest(template = DOCK, timeoutTicks = 120, batch = "wind")
    public static void windDriftsTheShip(GameTestHelper h) {
        com.airshipvoyage.physics.WorldEnvironment.testWind = new Vec3d(0, 0, 3);
        AirshipEntity ship = flyingShip(h, 12);
        double z0 = ship.getZ();
        h.runAfterDelay(60, () -> {
            com.airshipvoyage.physics.WorldEnvironment.testWind = null;
            double dz = ship.getZ() - z0;
            AirshipVoyage.LOGGER.info("[test] wind 3 m/s south for 3 s: drifted {} m, vz {}", String.format("%.2f", dz),
                    String.format("%.2f", ship.body().velocity().z()));
            h.assertTrue(dz > 0.2 && ship.body().velocity().z() > 0.2, "no drift: " + dz);
            ship.discard();
            h.succeed();
        });
    }

    private OpsGameTests() {}
}
