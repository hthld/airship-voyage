package com.airshipvoyage.assembly;

import com.airshipvoyage.collision.ShipRegistry;
import com.airshipvoyage.core.assembly.AssemblyPlan;
import com.airshipvoyage.core.assembly.AssemblyTargeting;
import com.airshipvoyage.core.assembly.ConstructionState;
import com.airshipvoyage.core.assembly.SiteCheck;
import com.airshipvoyage.core.math.Aabb;
import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.physics.AirshipEntity;
import com.airshipvoyage.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Строительство в мире (ТЗ, 4.2–4.4, 5.2): размещение проекта, выбор монтажной точки взглядом,
 * проверки, которым нужен мир (досягаемость, перекрытый доступ, занятое место), установка и спуск.
 * Правила, не зависящие от мира, — в {@code core.assembly}.
 */
public final class ConstructionSites {
    /** Как далеко можно указать место для проекта чертежом (корабль большой — с площадки его не обойти). */
    public static final double PLACE_REACH = 48;
    /** Радиус, в котором действует Shift+ПКМ чертежом (спуск / сводка о стройке). */
    public static final double SITE_RADIUS = 24;

    /** Монтажная точка под взглядом: корабль-стройка и точка его плана. */
    public record Aim(AirshipEntity ship, AssemblyTargeting.Target target) {}

    public enum Outcome {
        /** Взгляд не указывает на подходящую монтажную точку. */
        NO_TARGET,
        INSTALLED,
        /** Последняя деталь — корабль готов к спуску. */
        COMPLETED,
        /** Между игроком и точкой — блок (ТЗ 5.2: «не перекрыт ли доступ»). */
        VIEW_BLOCKED,
        /** Опоры не готовы. */
        MISSING_SUPPORT,
        /** В объёме детали стоит блок. */
        OCCUPIED_BY_BLOCK,
        /** В объёме детали кто-то стоит. */
        OCCUPIED_BY_ENTITY
    }

    public record InstallResult(Outcome outcome, String slot, List<String> missing, BlockPos at) {
        static InstallResult of(Outcome o) { return new InstallResult(o, "", List.of(), null); }
    }

    // ------------------------------------------------------------------ монтаж

    /** Точка под взглядом игрока для предмета {@code itemKey} — на клиенте (подсветка) и на сервере (установка). */
    public static Optional<Aim> findTarget(Level level, Player player, String itemKey) {
        return findTarget(level, player.getEyePosition(), player.getViewVector(1.0F), player.blockInteractionRange(), itemKey);
    }

    public static Optional<Aim> findTarget(Level level, Vec3 eye, Vec3 look, double reach, String itemKey) {
        AABB area = new AABB(eye, eye).inflate(reach + 1);
        Aim best = null;
        for (AirshipEntity ship : ShipRegistry.shipsIntersecting(level, area)) {
            ConstructionState state = ship.construction();
            if (state == null) continue;
            var t = AssemblyTargeting.find(state, pos(ship), ship.orientation(), vec(eye), vec(look).normalized(), reach, itemKey);
            if (t.isPresent() && (best == null || t.get().distance() < best.target().distance())) best = new Aim(ship, t.get());
        }
        return Optional.ofNullable(best);
    }

    /**
     * ПКМ деталью (ТЗ, 5.2): подходит ли предмет, дотягивается ли игрок, не перекрыт ли доступ, готовы ли опоры,
     * не стоит ли деталь уже, не мешают ли блоки и сущности. При успехе предмет расходуется (кроме творческого режима),
     * деталь сразу получает коллизию, звучит звук установки.
     */
    public static InstallResult tryInstall(ServerLevel level, Player player, ItemStack stack, String itemKey) {
        Optional<Aim> aim = findTarget(level, player, itemKey);
        if (aim.isEmpty()) return InstallResult.of(Outcome.NO_TARGET);
        AirshipEntity ship = aim.get().ship();
        AssemblyPlan.Step step = aim.get().target().step();
        double dist = aim.get().target().distance();

        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F).normalize();
        Vec3 hitPoint = eye.add(look.scale(dist));
        if (dist > 1e-3) {
            BlockHitResult clip = level.clip(new ClipContext(eye, hitPoint, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (clip.getType() == HitResult.Type.BLOCK && clip.getLocation().distanceTo(eye) < dist - 1e-3) {
                return new InstallResult(Outcome.VIEW_BLOCKED, step.slot(), List.of(), clip.getBlockPos());
            }
        }

        ConstructionState.Check check = ship.construction().check(step.slot(), itemKey);
        if (!check.ok()) {
            // WRONG_ITEM / ALREADY_INSTALLED сюда не доходят: выбор взглядом их уже отсеял
            return new InstallResult(Outcome.MISSING_SUPPORT, step.slot(), check.missing(), null);
        }

        List<OrientedBox> shapes = worldShapes(ship, step.slot());
        BlockPos blocked = firstBlockInside(level, shapes);
        if (blocked != null) return new InstallResult(Outcome.OCCUPIED_BY_BLOCK, step.slot(), List.of(), blocked);
        if (entityInside(level, shapes, ship)) return new InstallResult(Outcome.OCCUPIED_BY_ENTITY, step.slot(), List.of(), null);

        ship.construction().install(step.slot(), itemKey);
        ship.constructionChanged();
        if (!player.hasInfiniteMaterials()) stack.shrink(1);
        level.playSound(null, hitPoint.x, hitPoint.y, hitPoint.z,
                step.kind() == AssemblyPlan.Kind.CELL ? SoundEvents.WOOL_PLACE : SoundEvents.ANVIL_PLACE,
                SoundSource.BLOCKS, step.kind() == AssemblyPlan.Kind.CELL ? 1.0F : 0.4F, 1.3F);
        return new InstallResult(ship.construction().complete() ? Outcome.COMPLETED : Outcome.INSTALLED,
                step.slot(), List.of(), null);
    }

    /** Формы монтажной точки в мировых координатах. */
    public static List<OrientedBox> worldShapes(AirshipEntity ship, String slot) {
        Vec3d p = pos(ship);
        Quat q = ship.orientation();
        return ship.construction().plan().shapesOf(slot).stream().map(b -> b.transformed(p, q)).toList();
    }

    /** Первый блок с коллизией, пересекающий формы детали (по точной форме блока). */
    static BlockPos firstBlockInside(Level level, List<OrientedBox> shapes) {
        for (OrientedBox s : shapes) {
            Vec3d lo = s.aabbMin(), hi = s.aabbMax();
            for (BlockPos bp : BlockPos.betweenClosed(
                    floor(lo.x()), floor(lo.y()), floor(lo.z()), floor(hi.x()), floor(hi.y()), floor(hi.z()))) {
                BlockState state = level.getBlockState(bp);
                if (state.isAir()) continue;
                for (AABB part : state.getCollisionShape(level, bp).toAabbs()) {
                    AABB w = part.move(bp);
                    if (s.intersectsAabb(new Vec3d(w.minX, w.minY, w.minZ), new Vec3d(w.maxX, w.maxY, w.maxZ))) {
                        return bp.immutable();
                    }
                }
            }
        }
        return null;
    }

    /** Живая сущность внутри формы детали — её бы зажало деталью. */
    static boolean entityInside(Level level, List<OrientedBox> shapes, AirshipEntity ship) {
        for (OrientedBox s : shapes) {
            Vec3d lo = s.aabbMin(), hi = s.aabbMax();
            AABB area = new AABB(lo.x(), lo.y(), lo.z(), hi.x(), hi.y(), hi.z());
            for (Entity e : level.getEntities(ship, area, e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator())) {
                AABB b = e.getBoundingBox();
                if (s.intersectsAabb(new Vec3d(b.minX, b.minY, b.minZ), new Vec3d(b.maxX, b.maxY, b.maxZ))) return true;
            }
        }
        return false;
    }

    public static void report(Player player, InstallResult r) {
        Component part = partName(r.slot());
        Component msg = switch (r.outcome()) {
            case NO_TARGET -> null;
            case INSTALLED -> Component.translatable("message.airshipvoyage.build.installed", part);
            case COMPLETED -> Component.translatable("message.airshipvoyage.build.completed", part);
            case VIEW_BLOCKED -> Component.translatable("message.airshipvoyage.build.view_blocked", part);
            case MISSING_SUPPORT -> Component.translatable("message.airshipvoyage.build.missing_support", part, partList(r.missing()));
            case OCCUPIED_BY_BLOCK -> Component.translatable("message.airshipvoyage.build.occupied_block", part,
                    r.at().getX(), r.at().getY(), r.at().getZ());
            case OCCUPIED_BY_ENTITY -> Component.translatable("message.airshipvoyage.build.occupied_entity", part);
        };
        if (msg != null) player.displayClientMessage(msg, true);
    }

    // ------------------------------------------------------------------ размещение проекта (ТЗ, 4.2)

    /** Проект на месте, куда смотрит игрок, и результат проверки площадки. */
    public record ProjectAim(BlockPos anchor, SiteCheck.Placement placement, SiteCheck.Result result) {}

    public static Optional<ProjectAim> aimProject(Level level, Player player, AssemblyPlan plan) {
        HitResult hit = player.pick(PLACE_REACH, 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) return Optional.empty();
        BlockPos anchor = ((BlockHitResult) hit).getBlockPos();
        Vec3 look = player.getViewVector(1.0F);
        return Optional.of(check(level, plan, anchor, SiteCheck.quarterTurnsFacing(look.x, look.z)));
    }

    public static ProjectAim check(Level level, AssemblyPlan plan, BlockPos anchor, int quarterTurns) {
        SiteCheck.Placement p = SiteCheck.place(plan.layout(), anchor.getX(), anchor.getY(), anchor.getZ(), quarterTurns);
        return new ProjectAim(anchor, p, SiteCheck.check(plan, p, worldOf(level)));
    }

    /** Мир Minecraft для проверки площадки. */
    public static SiteCheck.World worldOf(Level level) {
        return new SiteCheck.World() {
            @Override
            public boolean solidTop(int x, int y, int z) {
                BlockPos bp = new BlockPos(x, y, z);
                return level.getBlockState(bp).isFaceSturdy(level, bp, Direction.UP);
            }

            @Override
            public boolean obstacle(int x, int y, int z) {
                BlockPos bp = new BlockPos(x, y, z);
                BlockState s = level.getBlockState(bp);
                return !s.isAir() && !s.getCollisionShape(level, bp).isEmpty();
            }

            @Override
            public boolean overlapsOther(Aabb box) {
                return !ShipRegistry.shipsIntersecting(level, new AABB(box.min().x(), box.min().y(), box.min().z(),
                        box.max().x(), box.max().y(), box.max().z())).isEmpty();
            }

            @Override public int minBuildY() { return level.getMinBuildHeight(); }
            @Override public int maxBuildY() { return level.getMaxBuildHeight(); }
        };
    }

    /** Поставить проект: корабль на стапеле, ни одной детали. Площадку надо проверить до вызова. */
    public static AirshipEntity spawnSite(ServerLevel level, AssemblyPlan plan, SiteCheck.Placement p) {
        AirshipEntity ship = new AirshipEntity(ModEntities.AIRSHIP.get(), level);
        ship.startConstruction(plan);
        ship.placeAt(p.origin(), p.rotation());
        level.addFreshEntity(ship);
        return ship;
    }

    public static Component problemMessage(SiteCheck.Result r) {
        return switch (r.problem()) {
            case NONE -> Component.translatable("message.airshipvoyage.site.ok");
            case OUT_OF_WORLD -> Component.translatable("message.airshipvoyage.site.out_of_world");
            case OVERLAPS_PROJECT -> Component.translatable("message.airshipvoyage.site.overlaps");
            case NO_SUPPORT -> Component.translatable("message.airshipvoyage.site.no_support",
                    r.unsupported(), r.supports(), r.x(), r.y(), r.z());
            case OBSTRUCTED -> Component.translatable("message.airshipvoyage.site.obstructed", r.x(), r.y(), r.z());
        };
    }

    // ------------------------------------------------------------------ спуск со стапеля

    /** Ближайшая стройка, в габарите которой (с запасом) стоит игрок. */
    public static Optional<AirshipEntity> nearestSite(Level level, Vec3 at) {
        return ShipRegistry.shipsIntersecting(level, new AABB(at, at).inflate(SITE_RADIUS)).stream()
                .filter(AirshipEntity::isUnderConstruction)
                .min(Comparator.comparingDouble(s -> s.getBoundingBox().distanceToSqr(at)));
    }

    /** Shift+ПКМ чертежом: законченную стройку — спустить, незаконченную — показать, что дальше. */
    public static boolean launchOrReport(Player player) {
        Optional<AirshipEntity> site = nearestSite(player.level(), player.position());
        if (site.isEmpty()) return false;
        AirshipEntity ship = site.get();
        ConstructionState s = ship.construction();
        if (s.complete()) {
            ship.launch();
            player.level().playSound(null, ship.getX(), ship.getY(), ship.getZ(), SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 1.0F, 0.6F);
            player.displayClientMessage(Component.translatable("message.airshipvoyage.build.launched"), false);
        } else {
            var next = s.available().stream().map(st -> partName(st.slot())).toList();
            player.displayClientMessage(Component.translatable("message.airshipvoyage.build.progress",
                    s.installedCount(), s.totalCount(), joined(next)), false);
        }
        return true;
    }

    // ------------------------------------------------------------------ служебное

    /** Название монтажной точки: «кормовое кольцо», «верхняя левая ферма»… */
    public static Component partName(String slot) {
        return Component.translatable("part.airshipvoyage." + slot);
    }

    private static Component partList(List<String> slots) {
        return joined(slots.stream().map(ConstructionSites::partName).toList());
    }

    private static Component joined(List<Component> items) {
        var out = Component.empty();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(items.get(i));
        }
        return out;
    }

    static Vec3d pos(Entity e) { return new Vec3d(e.getX(), e.getY(), e.getZ()); }
    static Vec3d vec(Vec3 v) { return new Vec3d(v.x, v.y, v.z); }
    private static int floor(double v) { return (int) Math.floor(v); }

    private ConstructionSites() {}
}
