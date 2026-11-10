package com.airshipvoyage.debug;

import com.airshipvoyage.AirshipVoyage;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.structure.PrototypeShip;
import com.airshipvoyage.damage.ShipBreaking;
import com.airshipvoyage.physics.AirshipEntity;
import com.airshipvoyage.registry.ModEntities;
import com.airshipvoyage.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.List;

/** Поломка корабля (ТЗ 18–19, AV-030): рукой, взрывом, пожаром водорода; сохранение; разборка на стапеле. */
@GameTestHolder(AirshipVoyage.MOD_ID)
@net.neoforged.neoforge.gametest.PrefixGameTestTemplate(false)
public final class DamageGameTests {
    private static final String DOCK = "ship_dock";

    private static AirshipEntity flyingShip(GameTestHelper h) {
        Vec3 p = h.absoluteVec(new Vec3(14, 12, 14));
        AirshipEntity ship = new AirshipEntity(ModEntities.AIRSHIP.get(), h.getLevel());
        ship.setLayoutById(PrototypeShip.LAYOUT_ID);
        ship.placeAt(new Vec3d(p.x, p.y, p.z), Quat.IDENTITY);
        ship.flight().weighOff(ship.environment());
        h.getLevel().addFreshEntity(ship);
        return ship;
    }

    private static Vec3 world(AirshipEntity ship, double x, double y, double z) {
        Vec3d w = ship.body().localToWorld(new Vec3d(x, y, z));
        return new Vec3(w.x(), w.y(), w.z());
    }

    private static List<ItemEntity> drops(GameTestHelper h, AirshipEntity ship, net.minecraft.world.item.Item item) {
        return h.getLevel().getEntitiesOfClass(ItemEntity.class, ship.getBoundingBox().inflate(6),
                e -> e.getItem().is(item));
    }

    /**
     * Удар рукой: верхний стабилизатор (прочность 10 + 12/5 = 12,4). Кулак без заряда — 2·1·0,2 = 0,4 за удар,
     * нужно 31 удар. Сломан — нет коробки, масса меньше на 12 кг, руля направления нет, стабилизатор выпал
     * предметом. Далеко стоящий не достаёт.
     */
    @GameTest(template = DOCK, batch = "damage")
    public static void finBrokenByHandFallsOffAsItem(GameTestHelper h) {
        AirshipEntity ship = flyingShip(h);
        Player far = h.makeMockPlayer(GameType.SURVIVAL);
        Vec3 fp = world(ship, -8.5, PrototypeShip.DECK_TOP, -20);
        far.setPos(fp.x, fp.y, fp.z);
        h.assertTrue(ShipBreaking.hit(far, ship, "fin_top").isEmpty() && ship.damage().hp("fin_top") == ship.damage().maxHp("fin_top"),
                "hit from 20 m away");
        Player p = h.makeMockPlayer(GameType.SURVIVAL);
        Vec3 at = world(ship, -8.5, 5.0, 1.2);
        p.setPos(at.x, at.y, at.z);
        double mass = ship.body().massKg();
        int shapes = ship.hullShapes().size();
        int hits = 0;
        while (!ship.damage().isDestroyed("fin_top") && hits < 100) {
            ShipBreaking.hit(p, ship, "fin_top");
            hits++;
        }
        AirshipVoyage.LOGGER.info("[test] fin broken by hand after {} hits; mass {} -> {} kg", hits,
                String.format("%.1f", mass), String.format("%.1f", ship.body().massKg()));
        h.assertTrue(ship.damage().isDestroyed("fin_top"), "fin not broken in 100 hits");
        h.assertTrue(hits == 31, "hits " + hits);
        h.assertTrue(ship.hullShapes().size() == shapes - 1, "shape still there");
        h.assertTrue(Math.abs(mass - 12 - ship.body().massKg()) < 1e-6, "mass " + ship.body().massKg());
        h.assertTrue(ship.flight().engines().size() == 2, "engines lost");
        h.assertTrue(!drops(h, ship, ModItems.TAIL_FIN.get()).isEmpty(), "no fin dropped");
        ship.discard();
        h.succeed();
    }

    /**
     * Взрыв силы 2 в 0,6 м за левой гондолой (урон 30·(1 − 0,1/4) ≈ 29 > прочности гондолы 24): гондола
     * разлетается предметом, летящим от взрыва, мотор один; кольцо (прочность 48,4) цело. Толчок
     * 400·2·(1 − 0,1/4) = 780 Н·с к правому борту; боковая масса с присоединённой 1736 + 1744 = 3480 кг →
     * Δv = 0,224 м/с по +Z.
     */
    @GameTest(template = DOCK, timeoutTicks = 100, batch = "damage")
    public static void blastNextToEngineBlowsItOff(GameTestHelper h) {
        AirshipEntity ship = flyingShip(h);
        Vec3 c = world(ship, 0, PrototypeShip.ENGINE_Y, -PrototypeShip.ENGINE_Z - 0.6);
        h.getLevel().explode(null, c.x, c.y, c.z, 2.0f, Level.ExplosionInteraction.NONE);
        h.assertTrue(ship.damage().isDestroyed("engine_port"), "port engine survived");
        h.assertTrue(!ship.damage().isDestroyed("ring_mid") && !ship.damage().isDestroyed("engine_stbd"), "too much destroyed: "
                + ship.damage().destroyed());
        h.assertTrue(ship.flight().engines().size() == 1, "engines " + ship.flight().engines().size());
        var pods = drops(h, ship, ModItems.ENGINE_POD.get());
        h.assertTrue(!pods.isEmpty(), "no debris");
        Vec3 away = pods.get(0).getDeltaMovement();
        double vz = ship.body().velocity().z();
        AirshipVoyage.LOGGER.info("[test] blast at the port engine: debris velocity {}, ship vz {} m/s", away, String.format("%.3f", vz));
        // взрыв снаружи левого борта (z = −7,1): гондола (центр z = −6,5) летит от него — к +Z
        h.assertTrue(away.z > 0.2, "debris should fly away from the blast, v " + away);
        h.assertTrue(vz > 0.1, "ship not pushed: vz " + vz);
        ship.discard();
        h.succeed();
    }

    /**
     * Пожар водорода: взрыв силы 2 под носовой камерой (до отсека 1,25 м: урон 30·(1 − 1,25/4) = 20,6 — камера
     * рвётся, но цела, а пламя ≥ 15 её поджигает). Вспышка водорода, затем камера горит (50 % в секунду) и
     * за ≈ 20 с выгорает: камеры нет, корабль тяжелеет и опускается.
     */
    @GameTest(template = DOCK, timeoutTicks = 900, batch = "fire")
    public static void hydrogenCellCatchesFireAndBurnsOut(GameTestHelper h) {
        AirshipEntity ship = flyingShip(h);
        ship.flight().setNeutral(true); // проверяется горение; без мешка корабль упал бы и разбился о пол дока (AV-040)
        int fwd = ship.flight().cellIndex("cell_fwd");
        boolean[] seenBurning = {false};
        int[] start = {-1};
        h.onEachTick(() -> {
            if (start[0] < 0) {
                start[0] = ship.tickCount;
                Vec3 c = world(ship, 5, -4.5, 0);
                h.getLevel().explode(null, c.x, c.y, c.z, 2.0f, Level.ExplosionInteraction.NONE);
                h.assertTrue(!ship.damage().isDestroyed("cell_fwd"), "blast destroyed the cell outright");
                return;
            }
            var cell = ship.flight().cells().get(fwd);
            seenBurning[0] |= cell.burning();
            if (ship.tickCount - start[0] == 40) h.assertTrue(seenBurning[0], "hydrogen did not catch fire");
            if (ship.tickCount - start[0] >= 40 && cell.destroyed() && ship.damage().isDestroyed("cell_fwd")) {
                AirshipVoyage.LOGGER.info("[test] hydrogen cell burnt out after {} ticks; destroyed: {}; ship vy {} m/s",
                        ship.tickCount - start[0], ship.damage().destroyed(), String.format("%.3f", ship.body().velocity().y()));
                h.assertTrue(ship.damage().isDestroyed("cell_fwd") && !ship.cellPresent(fwd), "cell still listed");
                ship.flight().setNeutral(false);
                h.assertTrue(ship.flight().balance(ship.environment()).heavinessKg() > 100, "ship should be heavy without the cell");
                ship.discard();
                h.succeed();
            }
        });
    }

    /** Повреждения переживают сохранение мира: сломанный стабилизатор не возвращается, урон камеры сохраняется. */
    @GameTest(template = DOCK, batch = "damage")
    public static void damageSurvivesSaveAndLoad(GameTestHelper h) {
        AirshipEntity ship = flyingShip(h);
        ship.hitSlot("fin_top", 100, null, false);
        ship.hitSlot("cell_aft", 10, null, false);
        CompoundTag tag = new CompoundTag();
        ship.saveWithoutId(tag);
        AirshipEntity copy = new AirshipEntity(ModEntities.AIRSHIP.get(), h.getLevel());
        copy.load(tag);
        h.assertTrue(copy.damage().isDestroyed("fin_top"), "fin came back");
        h.assertTrue(copy.hullShapes().size() == ship.hullShapes().size(), "shapes " + copy.hullShapes().size());
        h.assertTrue(Math.abs(copy.damage().hp("cell_aft") - 20) < 1e-9, "cell hp " + copy.damage().hp("cell_aft"));
        h.assertTrue(Math.abs(copy.body().massKg() - ship.body().massKg()) < 1e-6, "mass differs");
        ship.discard();
        h.succeed();
    }

    /** На стапеле удар снимает деталь (не ломает) вместе со всем, что на ней стоит, и отдаёт их предметами. */
    @GameTest(template = DOCK, batch = "damage")
    public static void slipwayPartsAreDismantled(GameTestHelper h) {
        var plan = com.airshipvoyage.core.assembly.AssemblyPlans.prototype();
        var anchor = net.minecraft.core.BlockPos.containing(h.absoluteVec(new Vec3(14, 2, 14)));
        double floor = anchor.getY();
        for (int y = anchor.getY(); y > h.getLevel().getMinBuildHeight(); y--) {
            var bp = new net.minecraft.core.BlockPos(anchor.getX(), y, anchor.getZ());
            if (!h.getLevel().getBlockState(bp).getCollisionShape(h.getLevel(), bp).isEmpty()) { floor = y; break; }
        }
        var base = new net.minecraft.core.BlockPos(anchor.getX(), (int) floor, anchor.getZ());
        var aim = com.airshipvoyage.assembly.ConstructionSites.check(h.getLevel(), plan, base, 0);
        h.assertTrue(aim.result().ok(), "site rejected: " + aim.result());
        AirshipEntity ship = com.airshipvoyage.assembly.ConstructionSites.spawnSite(h.getLevel(), plan, aim.placement());
        ship.construction().installAll();
        ship.constructionChanged();
        List<String> off = ship.hitSlot("ring_mid", 1000, null, true);
        h.assertTrue(off.containsAll(List.of("ring_mid", "keel", "engine_port")), "dismantled " + off);
        h.assertTrue(!ship.construction().isInstalled("keel") && ship.construction().isInstalled("ring_fwd"), "wrong parts left");
        h.assertTrue(!drops(h, ship, ModItems.RING_SEGMENT.get()).isEmpty() && !drops(h, ship, ModItems.KEEL_SECTION.get()).isEmpty(),
                "parts not returned");
        ship.discard();
        h.succeed();
    }

    /**
     * Ремонт (AV-033): сломанный стабилизатор снова виден как место для установки (опора — кормовое кольцо — цела),
     * ставится тем же предметом: коробка и руль на месте, масса прежняя. Порванный мешок ремкомплектом штопается
     * (прочность +20, целый — без утечки); сгоревший мешок вешается заново — пустой.
     */
    @GameTest(template = DOCK, batch = "damage")
    public static void brokenPartsAndCellsCanBeRepaired(GameTestHelper h) {
        AirshipEntity ship = flyingShip(h);
        double mass = ship.body().massKg();
        int shapes = ship.hullShapes().size();
        h.assertTrue(ship.installState() == null, "nothing to repair yet");
        ship.hitSlot("fin_top", 100, null, true);
        var state = ship.installState();
        h.assertTrue(state != null && !state.isInstalled("fin_top") && state.check("fin_top",
                com.airshipvoyage.core.assembly.AssemblyPlans.FIN).ok(), "fin not offered for repair");
        ship.repairSlot("fin_top");
        h.assertTrue(!ship.damage().isDestroyed("fin_top") && ship.hullShapes().size() == shapes, "fin not back");
        h.assertTrue(Math.abs(ship.body().massKg() - mass) < 1e-6, "mass " + ship.body().massKg());
        h.assertTrue(ship.installState() == null, "still something to repair");

        ship.hitSlot("cell_aft", 20, null, false);
        var aft = ship.flight().cells().get(ship.flight().cellIndex("cell_aft"));
        h.assertTrue(aft.leakPerSecond() > 0 && Math.abs(ship.damage().hp("cell_aft") - 10) < 1e-9, "cell not torn");
        double healed = ship.healSlot("cell_aft", com.airshipvoyage.damage.RepairKitItem.HEAL);
        h.assertTrue(Math.abs(healed - 20) < 1e-9 && aft.leakPerSecond() == 0, "patched: healed " + healed + ", leak " + aft.leakPerSecond());

        ship.hitSlot("cell_fwd", 100, null, false);
        int fwd = ship.flight().cellIndex("cell_fwd");
        h.assertTrue(!ship.cellPresent(fwd), "torn cell still hangs");
        ship.repairSlot("cell_fwd");
        var cell = ship.flight().cells().get(fwd);
        h.assertTrue(ship.cellPresent(fwd) && !cell.destroyed() && cell.amountMol() == 0, "new cell should hang empty");
        ship.discard();
        h.succeed();
    }

    /** Огниво по водородному мешку поджигает его на ближайшем тике (вспышка, затем горение); гелий не горит. */
    @GameTest(template = DOCK, timeoutTicks = 100, batch = "fire")
    public static void flintAndSteelIgnitesHydrogenCell(GameTestHelper h) {
        AirshipEntity ship = flyingShip(h);
        h.assertTrue(ship.igniteCell("cell_aft"), "not ignitable");
        var cell = ship.flight().cells().get(ship.flight().cellIndex("cell_aft"));
        h.runAfterDelay(3, () -> {
            h.assertTrue(cell.burning() || cell.destroyed(), "did not catch fire");
            ship.discard();
            h.succeed();
        });
    }

    /** Рецепты (AV-034): все предметы мода крафтятся; ферма — из 5 железных слитков, получается 2. */
    @GameTest(template = DOCK, batch = "damage")
    public static void allItemsHaveRecipes(GameTestHelper h) {
        var rm = h.getLevel().getRecipeManager();
        for (String id : List.of("truss_section", "ring_segment", "keel_section", "folded_gas_cell", "engine_pod", "tail_fin",
                "helm", "hydrogen_supply", "helium_supply", "hydrogen_supply_refill", "helium_supply_refill",
                "prototype_blueprint", "blueprint_table", "repair_kit")) {
            h.assertTrue(rm.byKey(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(AirshipVoyage.MOD_ID, id)).isPresent(),
                    "no recipe " + id);
        }
        var iron = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT);
        var e = net.minecraft.world.item.ItemStack.EMPTY;
        var input = net.minecraft.world.item.crafting.CraftingInput.of(3, 3, List.of(iron, e, iron, e, iron, e, iron, e, iron));
        var out = rm.getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, h.getLevel())
                .map(r -> r.value().assemble(input, h.getLevel().registryAccess())).orElse(e);
        h.assertTrue(out.is(ModItems.TRUSS_SECTION.get()) && out.getCount() == 2, "truss craft gave " + out);
        h.succeed();
    }

    private static net.minecraft.world.entity.monster.Zombie idleZombie(GameTestHelper h, Vec3 at) {
        var z = net.minecraft.world.entity.EntityType.ZOMBIE.create(h.getLevel());
        z.goalSelector.removeAllGoals(g -> true);
        z.targetSelector.removeAllGoals(g -> true);
        z.moveTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(z);
        return z;
    }

    /**
     * Мешки твёрдые (D-151), и кто внутри корпуса — выдавливается (D-152): стойка, поставленная в середину носового
     * мешка (наполнен на 88 %), за несколько тиков оказывается снаружи газа.
     */
    @GameTest(template = DOCK, timeoutTicks = 60, batch = "collision")
    public static void entityInsideGasCellIsPushedOut(GameTestHelper h) {
        AirshipEntity ship = flyingShip(h);
        int fwd = ship.flight().cellIndex("cell_fwd");
        h.runAfterDelay(2, () -> {
            var gas = ship.gasBox(fwd);
            h.assertTrue(gas != null, "full cell has no gas box");
            Vec3 c = world(ship, gas.center().x(), gas.center().y(), gas.center().z());
            var stand = new net.minecraft.world.entity.decoration.ArmorStand(h.getLevel(), c.x, c.y, c.z);
            stand.setNoGravity(true);
            h.getLevel().addFreshEntity(stand);
            h.runAfterDelay(30, () -> {
                var g = ship.gasBox(fwd).transformed(ship.body().position(), ship.orientation());
                var bb = stand.getBoundingBox();
                Vec3d mid = new Vec3d((bb.minX + bb.maxX) / 2, (bb.minY + bb.maxY) / 2, (bb.minZ + bb.maxZ) / 2);
                AirshipVoyage.LOGGER.info("[test] stand put inside the gas: now {} m from the gas box", String.format("%.2f", g.distanceTo(mid)));
                h.assertTrue(g.distanceTo(mid) > 0, "still inside the gas cell");
                stand.discard();
                ship.discard();
                h.succeed();
            });
        });
    }

    /**
     * Пункт 5 владельца: корабль садится на моба — тот получает урон (как от падающего блока) и вылетает из-под
     * корпуса. Зомби стоит на полу под килем; корабль с газом на 50 % падает на него.
     */
    @GameTest(template = DOCK, timeoutTicks = 200, batch = "collision")
    public static void shipLandingOnMobHurtsAndThrowsItOut(GameTestHelper h) {
        Vec3 floor = h.absoluteVec(new Vec3(14, 2, 14));
        Vec3 p = h.absoluteVec(new Vec3(14, 2 + 2.6 + 5.5, 14)); // низ киля в 2,6 м над полом, зомби — 1,95 м
        AirshipEntity ship = new AirshipEntity(ModEntities.AIRSHIP.get(), h.getLevel());
        ship.setLayoutById(PrototypeShip.LAYOUT_ID);
        ship.placeAt(new Vec3d(p.x, p.y, p.z), Quat.IDENTITY);
        ship.flight().setFillAll(0.5, ship.environment());
        ship.flight().setAutoTrim(false);
        h.getLevel().addFreshEntity(ship);
        var z = idleZombie(h, new Vec3(floor.x + 2, floor.y, floor.z));
        float hp0 = z.getHealth();
        h.runAfterDelay(80, () -> {
            Vec3d l = ship.body().worldToLocal(new Vec3d(z.getX(), z.getY(), z.getZ()));
            AirshipVoyage.LOGGER.info("[test] ship landed on a zombie: health {} -> {}, zombie local z {}", hp0, z.getHealth(),
                    String.format("%.2f", l.z()));
            h.assertTrue(z.getHealth() < hp0, "zombie not hurt");
            h.assertTrue(Math.abs(l.z()) > 1.0 || z.isDeadOrDying(), "zombie still under the keel, local z " + l.z());
            z.discard();
            ship.discard();
            h.succeed();
        });
    }

    /**
     * Стрелы (D-155): стрела, пущенная в носовой мешок, прошивает его (урон 5 → прочность 25, утечка) и летит
     * дальше; горящая стрела поджигает водород.
     */
    @GameTest(template = DOCK, timeoutTicks = 100, batch = "collision")
    public static void arrowsPierceAndFlamingArrowsIgniteCells(GameTestHelper h) {
        AirshipEntity ship = flyingShip(h);
        int fwd = ship.flight().cellIndex("cell_fwd");
        int aft = ship.flight().cellIndex("cell_aft");
        h.runAfterDelay(2, () -> {
            for (int k = 0; k < 2; k++) {
                int cell = k == 0 ? fwd : aft;
                var g = ship.gasBox(cell);
                Vec3 target = world(ship, g.center().x(), g.center().y(), g.center().z());
                Vec3 from = world(ship, g.center().x(), g.center().y(), -9);
                var arrow = new net.minecraft.world.entity.projectile.Arrow(h.getLevel(), from.x, from.y, from.z,
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW), null);
                arrow.shoot(target.x - from.x, target.y - from.y, target.z - from.z, 2.5f, 0);
                if (k == 1) arrow.igniteForSeconds(100);
                h.getLevel().addFreshEntity(arrow);
            }
            h.runAfterDelay(20, () -> {
                double hpF = ship.damage().hp("cell_fwd");
                var cAft = ship.flight().cells().get(aft);
                AirshipVoyage.LOGGER.info("[test] arrows: fwd cell hp {}, leak {}; aft burning {} destroyed {}", hpF,
                        ship.flight().cells().get(fwd).leakPerSecond(), cAft.burning(), cAft.destroyed());
                h.assertTrue(hpF < 30 && ship.flight().cells().get(fwd).leakPerSecond() > 0, "arrow did not pierce the cell");
                h.assertTrue(cAft.burning() || cAft.destroyed(), "flaming arrow did not ignite hydrogen");
                ship.discard();
                h.succeed();
            });
        });
    }

    /**
     * Тушение и огонь (D-153, D-154): горящий мешок поджигает зомби, стоящего вплотную к отсеку; ведро воды или
     * огнетушитель тушат мешок — горение прекращается, газ остаётся.
     */
    @GameTest(template = DOCK, timeoutTicks = 120, batch = "fire")
    public static void fireSpreadsToMobsAndCanBePutOut(GameTestHelper h) {
        AirshipEntity ship = flyingShip(h);
        int aft = ship.flight().cellIndex("cell_aft");
        var bay = ship.layout().cells().get(aft).bay();
        var z = idleZombie(h, world(ship, bay.center().x(), bay.center().y() - bay.halfExtents().y() - 2.2, 0));
        z.setNoGravity(true);
        h.assertTrue(ship.igniteCell("cell_aft"), "not ignited");
        h.runAfterDelay(45, () -> {
            var cell = ship.flight().cells().get(aft);
            h.assertTrue(cell.burning(), "cell is not burning");
            h.assertTrue(z.isOnFire(), "zombie next to the burning cell is not on fire");
            double mol = cell.amountMol();
            h.assertTrue(ship.extinguishCell("cell_aft"), "could not put out");
            h.runAfterDelay(10, () -> {
                AirshipVoyage.LOGGER.info("[test] fire put out: gas left {} mol (was {} when extinguished)", cell.amountMol(), mol);
                h.assertTrue(!cell.burning() && !cell.destroyed() && cell.amountMol() > 0, "fire went on");
                z.discard();
                ship.discard();
                h.succeed();
            });
        });
    }

    /**
     * Крушение (ТЗ 20, AV-032): сломали среднее кольцо — гондолы держались только на нём и отделяются обломками
     * (отдельными телами), а не исчезают предметами. Обломки без газа падают; у корабля моторов нет. Обломок
     * переживает сохранение мира: остаётся обломком из одной гондолы.
     */
    @GameTest(template = DOCK, timeoutTicks = 100, batch = "wreck")
    public static void brokenRingDropsEnginesAsWrecks(GameTestHelper h) {
        AirshipEntity ship = flyingShip(h);
        ship.hitSlot("ring_mid", 1000, null, true);
        h.assertTrue(ship.flight().engines().isEmpty(), "ship still has engines");
        h.assertTrue(!ship.damage().isDestroyed("keel"), "the rest of the hull should hold");
        List<AirshipEntity> wrecks = h.getLevel().getEntitiesOfClass(AirshipEntity.class, ship.getBoundingBox().inflate(4),
                AirshipEntity::isWreck);
        h.assertTrue(wrecks.size() == 2, "wrecks " + wrecks.size());
        for (AirshipEntity w : wrecks) {
            h.assertTrue(w.visibleParts().size() == 1 && w.visibleParts().get(0).slot().startsWith("engine"), "wreck parts "
                    + w.visibleParts().stream().map(p -> p.slot()).toList());
        }
        AirshipEntity w0 = wrecks.get(0);
        double y0 = w0.getY();
        h.runAfterDelay(20, () -> {
            AirshipVoyage.LOGGER.info("[test] engine wreck after 1 s: fell {} m, vy {}", String.format("%.2f", y0 - w0.getY()),
                    String.format("%.2f", w0.body().velocity().y()));
            h.assertTrue(w0.getY() < y0 - 0.3, "wreck does not fall");
            CompoundTag tag = new CompoundTag();
            w0.saveWithoutId(tag);
            AirshipEntity copy = new AirshipEntity(ModEntities.AIRSHIP.get(), h.getLevel());
            copy.load(tag);
            h.assertTrue(copy.isWreck() && copy.visibleParts().size() == 1, "wreck lost on load");
            for (AirshipEntity w : wrecks) w.discard();
            ship.discard();
            h.succeed();
        });
    }

    private DamageGameTests() {}
}
