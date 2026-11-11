package com.airshipvoyage.physics;

import com.airshipvoyage.AirshipVoyage;
import com.airshipvoyage.collision.ShipCollisions;
import com.airshipvoyage.collision.ShipRegistry;
import com.airshipvoyage.core.assembly.AssemblyPlan;
import com.airshipvoyage.core.assembly.AssemblyPlans;
import com.airshipvoyage.core.assembly.ConstructionState;
import com.airshipvoyage.core.damage.HullDamage;
import com.airshipvoyage.core.collision.HullCollider;
import com.airshipvoyage.core.flight.FlightModel;
import com.airshipvoyage.core.math.Aabb;
import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.physics.GasCell;
import com.airshipvoyage.core.physics.LiftingGas;
import com.airshipvoyage.core.rigid.RigidBody;
import com.airshipvoyage.core.structure.PartInstance;
import com.airshipvoyage.core.structure.ShipLayout;
import com.airshipvoyage.core.structure.ShipLayouts;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;

import java.util.List;

/**
 * Корабль в мире — одна серверная сущность (ARCHITECTURE.md, разд. 5).
 *
 * <p>Пункт 1 технического прототипа (README): собственная локальная система координат,
 * каркас из нескольких деталей, движение как твёрдого тела. Источник истины — {@link RigidBody}
 * на сервере; позиция сущности = начало локальных координат корпуса.
 *
 * <p>Синхронизация (ТЗ, 22.8): позицию и сглаживание берёт на себя обычный трекинг сущностей
 * Minecraft, ориентация и ID типа корабля передаются через {@link SynchedEntityData}.
 * Клиент физику не считает — только интерполирует.
 *
 * <p>Шаг 2 прототипа: сущности сталкиваются с деталями ({@link ShipCollisions}, mixin в {@code Entity.collide}),
 * стоящих на палубе корабль переносит и поворачивает за собой.
 *
 * <p>Шаг 3 прототипа: физику полёта ведёт {@link FlightModel} — вес, архимедова сила газовых камер,
 * балласт, утечки, контакт с блоками мира ({@link WorldEnvironment}). Наполнение и утечки камер
 * передаются клиентам для отрисовки мешков.
 *
 * <p>Шаг 5 прототипа (строительство, ТЗ 4–5): та же сущность служит строительным проектом «на стапеле»
 * ({@link #isUnderConstruction()}): физика не считается, коллизию и вид дают только установленные детали
 * ({@link ConstructionState}), прогресс сохраняется в NBT и передаётся клиентам. Спуск со стапеля ({@link #launch()})
 * просто снимает режим стройки — тот же корабль начинает летать, стоящие на палубе остаются на ней.
 */
public class AirshipEntity extends Entity {
    private static final EntityDataAccessor<String> DATA_LAYOUT =
            SynchedEntityData.defineId(AirshipEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Quaternionf> DATA_ORIENTATION =
            SynchedEntityData.defineId(AirshipEntity.class, EntityDataSerializers.QUATERNION);
    /** Наполнение и утечки камер для отрисовки: {"fill": [..], "leak": [..]}. */
    private static final EntityDataAccessor<CompoundTag> DATA_CELLS =
            SynchedEntityData.defineId(AirshipEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** Стройка: {"active": bool, "installed": [точки плана]}; пусто — корабль летает. */
    /** Трос швартовки (AV-038): точка мачты {x, y, z}; пусто — не пришвартован. */
    private static final EntityDataAccessor<CompoundTag> DATA_MOORING =
            SynchedEntityData.defineId(AirshipEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** Повреждения (AV-030): разрушенные детали и камеры, горящие камеры. */
    private static final EntityDataAccessor<CompoundTag> DATA_DAMAGE =
            SynchedEntityData.defineId(AirshipEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final EntityDataAccessor<CompoundTag> DATA_CONSTRUCTION =
            SynchedEntityData.defineId(AirshipEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** Доли мощности моторов для вращения винтов: {"t": [..]} по порядку {@code layout().engines()}. */
    private static final EntityDataAccessor<CompoundTag> DATA_ENGINES =
            SynchedEntityData.defineId(AirshipEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** Кто стоит у штурвала (UUID строкой, пусто — никто) — клиенту, чтобы перехватывать клавиши и рисовать приборы. */
    private static final EntityDataAccessor<String> DATA_HELM =
            SynchedEntityData.defineId(AirshipEntity.class, EntityDataSerializers.STRING);
    /** Ветер у корабля (мировой вектор, м/с) — для флюгера (D-169); обновляется раз в полсекунды. */
    private static final EntityDataAccessor<org.joml.Vector3f> DATA_WIND =
            SynchedEntityData.defineId(AirshipEntity.class, EntityDataSerializers.VECTOR3);
    /** Приборы поста (AV-013): телеграф, рули, скорость, высота, тяжесть, балласт, газ, люди на борту. */
    private static final EntityDataAccessor<CompoundTag> DATA_HUD =
            SynchedEntityData.defineId(AirshipEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** Обороты винта на экране при полном ходу, рад/тик (≈ 3 об/с — быстрее глаз видит строб). */
    private static final float PROP_RAD_PER_TICK_FULL = (float) (Math.PI * 6 / 20);

    private static final double DT = 1.0 / 20.0;
    /** Предел скорости для отладочных команд, м/с (≈ 135 км/ч — максимум LZ 129). */
    public static final double MAX_SPEED = 38.0;

    private ShipLayout layout;
    /** Физика полёта; считается только на сервере, на клиенте хранит лишь позу. */
    private FlightModel flight;
    // --- состояние камер для рендера (клиент) и для решения «пора ли отправить» (сервер) ---
    private float[] cellFill = new float[0];
    private boolean[] cellLeaking = new boolean[0];

    /**
     * Корабль на стапеле (ТЗ, 4.3–4.4, 5.2): физика не считается, коллизию и вид дают только установленные детали.
     * {@code null} — корабль спущен и летает.
     */
    private ConstructionState construction;
    /** Коллизия стройки, пересчитывается при каждой установке. */
    private List<OrientedBox> constructionShapes = List.of();

    // --- клиентская интерполяция ориентации ---
    private Quat orientation = Quat.IDENTITY;
    private Quat orientationO = Quat.IDENTITY;
    private Quat orientationTarget = Quat.IDENTITY;
    private int orientationLerpSteps;
    private int lerpSteps;
    private double lerpX, lerpY, lerpZ;

    public AirshipEntity(EntityType<? extends AirshipEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.noCulling = true;
        setLayout(ShipLayouts.defaultLayout());
    }

    // ------------------------------------------------------------------ данные

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_LAYOUT, ShipLayouts.defaultLayout().id());
        builder.define(DATA_ORIENTATION, new Quaternionf());
        builder.define(DATA_CELLS, new CompoundTag());
        builder.define(DATA_CONSTRUCTION, new CompoundTag());
        builder.define(DATA_DAMAGE, new CompoundTag());
        builder.define(DATA_MOORING, new CompoundTag());
        builder.define(DATA_ENGINES, new CompoundTag());
        builder.define(DATA_HELM, "");
        builder.define(DATA_HUD, new CompoundTag());
        builder.define(DATA_WIND, new org.joml.Vector3f());
    }

    // ------------------------------------------------------------------ стройка (ТЗ, 4–5)

    /** Сделать из корабля строительный проект по плану: ничего не установлено, физика стоит. */
    public void startConstruction(AssemblyPlan plan) {
        if (layout != plan.layout()) setLayout(plan.layout());
        setConstruction(new ConstructionState(plan));
    }

    private void setConstruction(ConstructionState state) {
        this.construction = state;
        constructionChanged();
    }

    /** После установки детали: пересчитать коллизию и разослать клиентам. */
    public void constructionChanged() {
        constructionShapes = construction == null ? List.of() : List.copyOf(construction.installedShapes());
        if (!level().isClientSide) {
            CompoundTag tag = new CompoundTag();
            if (construction != null) {
                tag.putBoolean("active", true);
                tag.put("installed", strings(construction.installed()));
            }
            entityData.set(DATA_CONSTRUCTION, tag);
        }
    }

    public boolean isUnderConstruction() { return construction != null; }

    /** Прогресс стройки (null у летающего корабля). */
    public ConstructionState construction() { return construction; }

    /**
     * Спуск со стапеля (ТЗ 5.4, этап X): проект становится летающим кораблём. Мешки подвешены пустыми —
     * газ можно закачать до спуска (этап IX, ТЗ 7.3). Спуск сохраняет газ и повреждения камер.
     */
    public void launch() {
        if (construction == null) return;
        List<String> noInstrument = construction.missingOptional();
        flight.massChanged();
        flight.place(new Vec3d(getX(), getY(), getZ()), flight.body().orientation());
        setConstruction(null);
        // не поставленные приборы (D-169) — «пустые места»: их можно поставить и на летающий корабль, как при ремонте
        if (!noInstrument.isEmpty()) {
            damage().destroy(noInstrument);
            applyDamageState();
            syncDamage();
        }
        syncCells(true);
    }

    /** Стоит ли на корабле деталь {@code slot} (у стройки — установлена, у летающего — цела). */
    public boolean hasPart(String slot) {
        for (PartInstance p : visibleParts()) if (p.slot().equals(slot)) return true;
        return false;
    }

    /** Ветер у корабля, мировой вектор (куда движется воздух), м/с — для флюгера на клиенте. */
    public Vec3d windForRender() {
        org.joml.Vector3f w = entityData.get(DATA_WIND);
        return new Vec3d(w.x(), w.y(), w.z());
    }

    /** Коллизионные формы в локальных координатах корпуса: у стройки — только установленные детали. */
    public List<OrientedBox> hullShapes() {
        return construction != null ? constructionShapes : shapeLayout().hullShapes();
    }

    /** Детали для рендера: у стройки — только установленные. */
    public List<PartInstance> visibleParts() {
        return construction != null ? construction.installedParts() : shapeLayout().parts();
    }

    /** Камера {@code index} (по {@code layout().cells()}) подвешена — у стройки только установленные. */
    public boolean cellPresent(int index) {
        if (construction != null) return construction.isInstalled(layout.cells().get(index).id());
        return index >= cellGone.length || !cellGone[index];
    }

    private void setLayout(ShipLayout newLayout) {
        FlightModel old = flight;
        this.layout = newLayout;
        this.damage = null;
        this.intact = null;
        this.cellGone = new boolean[newLayout.cells().size()];
        this.cellBurning = new boolean[newLayout.cells().size()];
        this.flight = ShipLayouts.newFlightModel(newLayout);
        if (old != null) {
            // смена типа сохраняет позу и движение
            flight.place(old.body().position(), old.body().orientation());
            flight.body().setVelocity(old.body().velocity());
            flight.body().setAngularVelocity(old.body().angularVelocity());
            flight.setNeutral(old.neutral());
        } else {
            flight.place(new Vec3d(getX(), getY(), getZ()), Quat.IDENTITY);
        }
        if (construction != null && construction.plan().layout() != newLayout) setConstruction(null);
        cellFill = new float[newLayout.cells().size()];
        cellLeaking = new boolean[newLayout.cells().size()];
        if (!level().isClientSide) {
            entityData.set(DATA_LAYOUT, newLayout.id());
            syncCells(true);
            syncEngines(true);
        }
        reapplyPosition();
    }

    // ------------------------------------------------------------------ повреждения, взрывы, пожар (ТЗ 18–19, AV-030)

    private HullDamage damage;
    /** Чертёж без разрушенных деталей (null — всё цело). */
    private ShipLayout intact;
    private boolean[] cellGone = new boolean[0], cellBurning = new boolean[0];
    /** Камеры, которые надо поджечь на ближайшем тике (не внутри обработки чужого взрыва). */
    private final java.util.Set<Integer> pendingIgnition = new java.util.LinkedHashSet<>();
    /** Урон деталям на стапеле (там деталь не разрушается, а снимается). */
    private final java.util.Map<String, Double> siteDamage = new java.util.HashMap<>();

    public HullDamage damage() {
        // без обвала по опорам плана: что потеряло связь с корпусом, отделяется обломком (splitIfDisconnected)
        if (damage == null) damage = new HullDamage(layout, null);
        return damage;
    }

    private ShipLayout shapeLayout() { return intact != null ? intact : layout; }

    public boolean cellBurningForRender(int i) { return i < cellBurning.length && cellBurning[i]; }

    private boolean anyBurning() {
        for (GasCell c : flight.cells()) if (c.burning()) return true;
        return false;
    }

    private int[] burningIndices() {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int i = 0; i < flight.cells().size(); i++) if (flight.cells().get(i).burning()) out.add(i);
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private int cellIndexOf(String id) {
        for (int i = 0; i < layout.cells().size(); i++) if (layout.cells().get(i).id().equals(id)) return i;
        return -1;
    }

    /** Пересобрать корпус по списку разрушенного (обе стороны). Не осталось деталей — корабль исчезает. */
    private void applyDamageState() {
        repairView = null;
        entityShapesCache = null; // мешок мог порваться посреди тика — твёрдого газа больше нет
        java.util.Set<String> partsGone = new java.util.HashSet<>();
        if (damage != null) {
            for (String s : damage.destroyed()) if (cellIndexOf(s) < 0) partsGone.add(s);
        }
        ShipLayout cut = HullDamage.without(layout, partsGone);
        if (cut == null) {
            if (!level().isClientSide) discard();
            return;
        }
        intact = cut == layout ? null : cut;
        if (!level().isClientSide) flight.replaceLayout(cut); // клиенту модель полёта не нужна
        cellGone = new boolean[layout.cells().size()];
        if (damage != null) {
            for (String s : damage.destroyed()) {
                int i = cellIndexOf(s);
                if (i < 0) continue;
                cellGone[i] = true;
                if (!level().isClientSide && !flight.cells().get(i).destroyed()) flight.destroyCell(i);
            }
        }
        if (!level().isClientSide && helmsman != null && damage != null && damage.isDestroyed(com.airshipvoyage.control.HelmService.HELM_PART)) {
            com.airshipvoyage.control.HelmService.release(this);
        }
    }

    /** Клиент: применить присланные повреждения к нынешнему чертежу. */
    private void applyDamageTag() {
        CompoundTag d = entityData.get(DATA_DAMAGE);
        damage().restore(java.util.Map.of(), readStrings(d, "gone"));
        applyDamageState();
        cellBurning = new boolean[layout.cells().size()];
        for (int i : d.getIntArray("burn")) if (i >= 0 && i < cellBurning.length) cellBurning[i] = true;
    }

    private void syncDamage() {
        if (level().isClientSide) return;
        CompoundTag d = new CompoundTag();
        d.put("gone", strings(damage().destroyed()));
        d.putIntArray("burn", burningIndices());
        entityData.set(DATA_DAMAGE, d);
    }

    /**
     * Газ в мешке {@code i} в осях корпуса — то, что видно, и то, обо что ударяются (D-151): мешок висит под
     * верхом отсека, по высоте — пропорционально наполнению, по сечению раздувается раньше (√f).
     * null — мешка нет или он почти пуст (меньше 5 %).
     */
    public OrientedBox gasBox(int i) {
        if (!cellPresent(i) || i >= cellFill.length) return null;
        // сервер — из модели (последнее отправленное клиентам может отставать), клиент — присланное
        double f = Math.min(1.0, level().isClientSide ? cellFill[i] : flight.fill(i, environment()));
        if (f < 0.05) return null;
        OrientedBox bay = layout.cells().get(i).bay();
        Vec3d h = bay.halfExtents();
        double side = 0.55 + 0.45 * Math.sqrt(f), height = 2 * h.y() * f, top = bay.center().y() + h.y();
        return new OrientedBox(new Vec3d(bay.center().x(), top - height / 2, bay.center().z()),
                new Vec3d(h.x() * side, height / 2, h.z() * side), bay.rotation());
    }

    /**
     * Формы для столкновений с людьми, мобами, предметами: детали и мешки с газом (D-151). Кэш на тик: их просит
     * каждый шаг каждой сущности рядом с кораблём, а пересчёт мешка — это расчёт наполнения по атмосфере.
     */
    public List<OrientedBox> entityShapes() {
        if (entityShapesCache != null && entityShapesTick == tickCount && entityShapesHull == hullShapes()) return entityShapesCache;
        List<OrientedBox> out = new java.util.ArrayList<>(hullShapes());
        for (int i = 0; i < layout.cells().size(); i++) {
            OrientedBox g = gasBox(i);
            if (g != null) out.add(g);
        }
        entityShapesCache = List.copyOf(out);
        entityShapesTick = tickCount;
        entityShapesHull = hullShapes();
        return entityShapesCache;
    }

    private List<OrientedBox> entityShapesCache, entityShapesHull;
    private int entityShapesTick = -1;

    /**
     * Полный ремонт для проверок (`/airship repair`): сломанные детали и мешки на месте, урон снят, огонь потушен,
     * утечки заделаны, баки полны. Газ в заново подвешенных мешках — пустой.
     */
    public void repairAll() {
        if (level().isClientSide || construction != null) return;
        for (String s : List.copyOf(damage().destroyed())) {
            int ci = cellIndexOf(s);
            if (ci >= 0) flight.repairCell(ci);
        }
        damage().restore(java.util.Map.of(), List.of());
        for (GasCell c : flight.cells()) {
            c.extinguish();
            c.setLeakPerSecond(0);
        }
        pendingIgnition.clear();
        applyDamageState(); // моторы восстановленных гондол возвращаются в replaceLayout
        for (var e : flight.engines()) e.refuel(com.airshipvoyage.core.propulsion.Engine.TANK_KG);
        flight.massChanged();
        syncDamage();
        syncCells(true);
        syncEngines(true);
    }

    /** На сколько коробка сущности должна погрузиться в деталь, чтобы её выдавливали, м. */
    private static final double PUSH_DEPTH = 0.05;

    /** Корабль «садится» на сущность, если в её точке корпус опускается быстрее этого, м/тик (0,4 м/с). */
    private static final double LANDING_SINK_PER_TICK = 0.02;

    /**
     * Кто оказался внутри корпуса — корабль наехал, поднялся под ним, опустился на него (D-152), — того
     * выдавить наружу по кратчайшему пути, а если корабль двигался — по его ходу. Выдавливание — сдвигом, без
     * толчка скоростью: корабль в полёте не «батут» (D-168).
     *
     * <p>Урон и выброс — только когда корабль <b>опускается</b> на прижатого к земле (в его точке корпус идёт вниз
     * быстрее {@link #LANDING_SINK_PER_TICK}): урон как от падающего блока (4 + 20 × скорость корпуса в блоках/тик),
     * мягкий выброс вбок из-под корпуса. Прижатого боком или снизу просто сдвигает вбок, сколько пускают блоки.
     * Сервер — мобы и предметы; клиент — свой игрок; игрока ранит сервер.
     */
    private void pushOutEntities(Vec3d oldPos, Quat oldRot) {
        Vec3d pos = new Vec3d(getX(), getY(), getZ());
        Quat rot = orientation();
        List<OrientedBox> shapes = entityShapes();
        boolean client = level().isClientSide;
        for (Entity e : level().getEntities(this, getBoundingBox().inflate(0.5), x -> !(x instanceof AirshipEntity)
                && !x.isSpectator() && !x.noPhysics && !x.isPassenger())) {
            boolean localPlayer = e instanceof net.minecraft.world.entity.player.Player p && p.isLocalPlayer();
            if (client && !localPlayer) continue;
            AABB bb = e.getBoundingBox();
            Vec3d c = new Vec3d((bb.minX + bb.maxX) / 2, (bb.minY + bb.maxY) / 2, (bb.minZ + bb.maxZ) / 2);
            Vec3d lh = HullCollider.localHalfExtents(new Vec3d(bb.getXsize() / 2, bb.getYsize() / 2, bb.getZsize() / 2), rot);
            Vec3d lc = rot.inverseRotate(c.sub(pos));
            Vec3d lo = lc.sub(lh), hi = lc.add(lh);
            double[] exit = new double[6]; // +x −x +y −y +z −z: насколько сдвинуть, чтобы выйти из всех пересечённых
            boolean any = false;
            for (OrientedBox s : shapes) {
                Vec3d sMin = s.aabbMin(), sMax = s.aabbMax();
                boolean deep = true;
                for (int a = 0; a < 3 && deep; a++) {
                    if (Math.min(hi.get(a) - sMin.get(a), sMax.get(a) - lo.get(a)) <= PUSH_DEPTH) deep = false;
                }
                if (!deep) continue;
                any = true;
                for (int a = 0; a < 3; a++) {
                    exit[2 * a] = Math.max(exit[2 * a], sMax.get(a) - lo.get(a));
                    exit[2 * a + 1] = Math.max(exit[2 * a + 1], hi.get(a) - sMin.get(a));
                }
            }
            if (!any || exit[2] <= HullCollider.MAX_SINK) continue; // слегка утонул сверху — это стоящий, им занят collide
            // как корабль сдвинулся в этой точке за тик (в осях корпуса): навстречу ему и выдавливаем
            Vec3d shipMove = lc.sub(oldRot.inverseRotate(c.sub(oldPos))).scale(-1);
            int best = 0;
            double min = Double.MAX_VALUE;
            for (int k = 0; k < 6; k++) min = Math.min(min, exit[k]);
            double bestScore = -Double.MAX_VALUE;
            for (int k = 0; k < 6; k++) {
                if (exit[k] > 3 * min + 0.3) continue;
                Vec3d dir = axisDir(k);
                double score = shipMove.length() > 0.01 ? dir.dot(shipMove.normalized()) - exit[k] * 0.1 : -exit[k];
                if (score > bestScore) { bestScore = score; best = k; }
            }
            Vec3d pushW = rot.rotate(axisDir(best).scale(exit[best] + 0.01));
            net.minecraft.world.phys.Vec3 want = new net.minecraft.world.phys.Vec3(pushW.x(), pushW.y(), pushW.z());
            net.minecraft.world.phys.Vec3 allowed = Entity.collideBoundingBox(e, want, bb, level(), List.of());
            boolean pinned = allowed.length() < want.length() * 0.5;
            boolean serverPlayer = !client && e instanceof net.minecraft.world.entity.player.Player;
            if (!pinned) {
                // сдвиг, а не толчок скоростью: импульс копился за тики и подбрасывал игрока (D-168).
                // Своего игрока выдавливает его клиент.
                if (!serverPlayer) e.setPos(e.getX() + allowed.x, e.getY() + allowed.y, e.getZ() + allowed.z);
                continue;
            }
            // прижат: выход — вбок, в ближайшую сторону по горизонтали корпуса
            int side = exit[0] <= exit[1] ? 0 : 1;
            int sideZ = exit[4] <= exit[5] ? 4 : 5;
            if (exit[sideZ] < exit[side]) side = sideZ;
            Vec3d out = rot.rotate(axisDir(side));
            boolean landing = rot.rotate(shipMove).y() < -LANDING_SINK_PER_TICK;
            if (!landing) {
                // корабль идёт боком или поднимается — без урона и без выброса: сдвиг вбок, в ближайшую свободную
                // сторону, не больше 0,5 м за тик (выход вдоль киля бывает 10 м — не телепортировать)
                if (serverPlayer) continue;
                Integer[] order = {0, 1, 4, 5};
                java.util.Arrays.sort(order, java.util.Comparator.comparingDouble(k -> exit[k]));
                for (int k : order) {
                    Vec3d step = rot.rotate(axisDir(k)).scale(Math.min(exit[k] + 0.01, 0.5));
                    var moved = Entity.collideBoundingBox(e, new net.minecraft.world.phys.Vec3(step.x(), 0, step.z()), bb, level(), List.of());
                    if (moved.horizontalDistance() > 0.05) {
                        e.setPos(e.getX() + moved.x, e.getY(), e.getZ() + moved.z);
                        break;
                    }
                }
                continue;
            }
            if (client) continue; // придавленного посадкой ранит и выбрасывает сервер
            double speed = shipMove.length();
            if (e instanceof net.minecraft.world.entity.LivingEntity living) {
                living.hurt(damageSources().fallingBlock(this), (float) (4 + 20 * speed));
            }
            e.setDeltaMovement(out.x() * 0.35, 0.15, out.z() * 0.35); // мягко из-под корпуса, не вверх
            e.hurtMarked = true;
        }
    }

    private static Vec3d axisDir(int k) {
        double s = (k % 2 == 0) ? 1 : -1;
        return switch (k / 2) {
            case 0 -> new Vec3d(s, 0, 0);
            case 1 -> new Vec3d(0, s, 0);
            default -> new Vec3d(0, 0, s);
        };
    }

    /** Пришвартовать нос к точке мачты (сервер). */
    public void moor(Vec3d anchor) {
        flight.moor(anchor);
        syncMooring();
    }

    public void unmoor() {
        flight.unmoor();
        syncMooring();
    }

    private void syncMooring() {
        if (level().isClientSide) return;
        CompoundTag t = new CompoundTag();
        Vec3d a = flight.mooringAnchor();
        if (a != null) {
            t.putDouble("x", a.x());
            t.putDouble("y", a.y());
            t.putDouble("z", a.z());
        }
        entityData.set(DATA_MOORING, t);
    }

    /** Точка мачты для рендера троса; null — не пришвартован. */
    public Vec3d mooringAnchorForRender() {
        CompoundTag t = entityData.get(DATA_MOORING);
        return t.contains("x") ? new Vec3d(t.getDouble("x"), t.getDouble("y"), t.getDouble("z")) : null;
    }

    /** Пик скорости удара по детали в нынешнем касании и тик, когда она последний раз касалась. */
    private final java.util.Map<String, Double> impactPeaks = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> impactSeen = new java.util.HashMap<>();

    /** Урон удара на скорости {@code v}: 6·(v − 3)², до 3 м/с — 0. */
    public static double impactDamage(double v) {
        return v <= SAFE_IMPACT_SPEED ? 0 : 6 * (v - SAFE_IMPACT_SPEED) * (v - SAFE_IMPACT_SPEED);
    }

    /** Удар о блок до этой скорости сближения безопасен, м/с (мягкая посадка). */
    public static final double SAFE_IMPACT_SPEED = 3.0;

    /**
     * Удары о землю и скалы (ТЗ 18, 20; AV-040): деталь, врезавшаяся в блок быстрее {@value #SAFE_IMPACT_SPEED} м/с,
     * получает урон 6·(v − 3)²: 4 м/с — 6 (вмятина), 5 м/с — 24 (ферма, гондола, оперение — вдребезги), 6 м/с — 54
     * (ломается и кольцо). Корабль без мешка, упавший с высоты, разбивается. Разрушенное разлетается.
     */
    private void applyImpacts(java.util.Map<String, Double> impacts) {
        // один удар — один урон: пока деталь в касании, корпус тормозится несколько тиков (4 → 3 → 2 м/с), и урон
        // считается по наибольшей скорости касания, а не складывается с каждого тика (найдено тестом жёсткой посадки)
        impactPeaks.entrySet().removeIf(en -> !impacts.containsKey(en.getKey()) && tickCount - impactSeen.getOrDefault(en.getKey(), 0) > 10);
        if (impacts.isEmpty()) return;
        double worst = 0;
        for (var e : impacts.entrySet()) {
            double v = e.getValue();
            impactSeen.put(e.getKey(), tickCount);
            double before = impactPeaks.getOrDefault(e.getKey(), 0.0);
            if (v <= before) continue;
            impactPeaks.put(e.getKey(), v);
            double dmg = impactDamage(v) - impactDamage(before);
            if (dmg <= 0) continue;
            worst = Math.max(worst, v);
            hitSlot(e.getKey(), dmg, null, true);
        }
        if (worst > SAFE_IMPACT_SPEED && level() instanceof net.minecraft.server.level.ServerLevel sl) {
            sl.playSound(null, getX(), getY(), getZ(), net.minecraft.sounds.SoundEvents.ANVIL_LAND,
                    net.minecraft.sounds.SoundSource.BLOCKS, (float) Math.min(1.5, worst / 4), 0.6f);
        }
    }

    /** Потушить мешок (ведро воды, огнетушитель). @return горел ли */
    public boolean extinguishCell(String slot) {
        int ci = cellIndexOf(slot);
        if (ci < 0 || level().isClientSide || !flight.cells().get(ci).extinguish()) return false;
        pendingIgnition.remove(ci);
        Vec3d c = flight.body().localToWorld(layout.cells().get(ci).bay().center());
        if (level() instanceof net.minecraft.server.level.ServerLevel sl) {
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.CLOUD, c.x(), c.y(), c.z(), 40, 2.5, 2.0, 2.5, 0.05);
            sl.playSound(null, c.x(), c.y(), c.z(), net.minecraft.sounds.SoundEvents.FIRE_EXTINGUISH, net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 0.8f);
        }
        syncDamage();
        return true;
    }

    /** Во что попадает луч (удар, стрела, огниво): деталь — её коробки, мешок — объём газа, пустой — отсек. */
    public List<OrientedBox> hitShapes(String slot) {
        int ci = cellIndexOf(slot);
        if (ci < 0 || gasBox(ci) == null) return slotShapes(slot);
        return List.of(gasBox(ci).transformed(new Vec3d(getX(), getY(), getZ()), orientation()));
    }

    /** Мировые формы слота: деталь — её коробки, камера — отсек. Пусто — слота нет или он уже разрушен. */
    public List<OrientedBox> slotShapes(String slot) {
        Vec3d pos = new Vec3d(getX(), getY(), getZ());
        List<OrientedBox> out = new java.util.ArrayList<>();
        for (PartInstance p : visibleParts()) {
            if (p.slot().equals(slot)) for (OrientedBox b : p.hullShapes()) out.add(b.transformed(pos, orientation()));
        }
        int ci = cellIndexOf(slot);
        if (ci >= 0 && cellPresent(ci)) out.add(layout.cells().get(ci).bay().transformed(pos, orientation()));
        return out;
    }

    /**
     * Урон по слоту (деталь или камера) — удар рукой, взрыв, огонь. Летающий корабль: деталь теряет прочность и
     * разрушается, отваливается всё, что держалось только на ней; камера ещё и рвётся (растёт утечка).
     * На стапеле деталь не ломается, а снимается вместе со всем, что на ней стоит (AV-019).
     *
     * @param from      откуда пришёл удар (обломки летят от него); null — просто падают
     * @param dropItems отдавать ли детали предметами (выживание, взрыв); false — творческий режим
     * @return разрушенные или снятые слоты
     */
    public List<String> hitSlot(String slot, double amount, net.minecraft.world.phys.Vec3 from, boolean dropItems) {
        if (level().isClientSide || !(amount > 0)) return List.of();
        if (construction != null) {
            if (!construction.isInstalled(slot)) return List.of();
            double d = siteDamage.merge(slot, amount, Double::sum);
            if (d < damage().maxHp(slot)) return List.of();
            List<String> off = construction.uninstall(slot);
            off.forEach(siteDamage::remove);
            for (String s : off) {
                int ci = cellIndexOf(s);
                if (ci >= 0) flight.cells().get(ci).setAmountMol(0);
            }
            spawnDebris(off, from, dropItems, false);
            constructionChanged();
            syncCells(true);
            return off;
        }
        List<String> lost = damage().hit(slot, amount);
        int ci = cellIndexOf(slot);
        if (ci >= 0 && lost.isEmpty()) {
            GasCell c = flight.cells().get(ci);
            c.setLeakPerSecond(c.leakPerSecond() + amount / HullDamage.CELL_HP * 0.02); // рваная оболочка
        }
        onDestroyed(lost, from, dropItems);
        return lost;
    }

    /**
     * Что можно поставить: у стройки — её состояние; у летающего корабля с разрушенными деталями — «стройка» из
     * всего уцелевшего (ремонт: сломанное ставится заново тем же предметом, на целые опоры, AV-033). null — нечего.
     */
    public ConstructionState installState() {
        if (construction != null) return construction;
        // обломок — остов для разборки, а не основа нового корабля (ревью: вокруг гондолы-обломка можно было
        // поставить кольца, киль и мешки и получить летающий корпус; вокруг каждого обломка висели «призраки» корабля)
        if (wreck || damage == null || damage.destroyed().isEmpty()) return null;
        var plan = AssemblyPlans.byLayoutId(layout.id()).orElse(null);
        if (plan == null) return null;
        if (repairView == null) {
            List<String> present = new java.util.ArrayList<>();
            for (var s : plan.steps()) if (!damage.isDestroyed(s.slot())) present.add(s.slot());
            repairView = ConstructionState.restore(plan, present);
        }
        return repairView;
    }

    private ConstructionState repairView;

    /** Сломанная деталь или камера поставлена заново (ремонт). Камера — пустая, газ надо закачать. */
    public void repairSlot(String slot) {
        if (level().isClientSide || damage == null || !damage.repair(slot)) return;
        int ci = cellIndexOf(slot);
        if (ci >= 0) flight.repairCell(ci);
        applyDamageState();
        syncDamage();
        syncCells(true);
    }

    /**
     * Ремкомплект (AV-033): снять до {@code hp} урона детали или камеры; камере — ещё и заштопать дыры
     * (утечка падает вчетверо, а целая — до нуля). Горящую камеру не заштопать. @return сколько снято урона
     */
    public double healSlot(String slot, double hp) {
        if (level().isClientSide || construction != null) return 0;
        int ci = cellIndexOf(slot);
        if (ci >= 0 && flight.cells().get(ci).burning()) return 0;
        double healed = damage().heal(slot, hp);
        if (ci >= 0) {
            GasCell c = flight.cells().get(ci);
            c.setLeakPerSecond(damage().hp(slot) >= damage().maxHp(slot) - 1e-9 ? 0 : c.leakPerSecond() * 0.25);
            if (healed == 0 && c.leakPerSecond() > 0) healed = 1e-9;
            syncCells(true);
        }
        return healed;
    }

    /** Поджечь камеру (огниво, огненный шар): загорится на ближайшем тике, если там водород. */
    public boolean igniteCell(String slot) {
        int ci = cellIndexOf(slot);
        if (ci < 0 || level().isClientSide || !cellPresent(ci) || flight.cells().get(ci).burning()
                || flight.cells().get(ci).gas() != com.airshipvoyage.core.physics.LiftingGas.HYDROGEN
                || flight.cells().get(ci).amountMol() < 1) return false;
        pendingIgnition.add(ci);
        return true;
    }

    // ------------------------------------------------------------------ крушение: обломки (ТЗ 20, фаза 9, AV-032)

    /** Обломок разбитого корабля: без газа, без балласта — падает и лежит остовом, разбирается руками. */
    private boolean wreck;

    public boolean isWreck() { return wreck; }

    /**
     * Раскол (ТЗ 20): если после разрушения корпус распался на несвязные части (детали держатся, только касаясь
     * друг друга — {@link com.airshipvoyage.core.damage.Connectivity}), самая тяжёлая остаётся кораблём, остальные
     * отделяются обломками — отдельными телами со скоростью своей точки корпуса.
     */
    private void splitIfDisconnected() {
        if (level().isClientSide || construction != null || isRemoved()) return;
        var comps = com.airshipvoyage.core.damage.Connectivity.components(shapeLayout().parts());
        if (comps.size() <= 1) return;
        List<String> off = new java.util.ArrayList<>();
        for (int c = 1; c < comps.size(); c++) {
            spawnWreck(comps.get(c));
            off.addAll(comps.get(c));
        }
        damage().destroy(off);
        applyDamageState();
        syncDamage();
        syncCells(true);
    }

    private void spawnWreck(java.util.Set<String> keep) {
        AirshipEntity w = new AirshipEntity((net.minecraft.world.entity.EntityType<? extends AirshipEntity>) getType(), level());
        w.setLayoutById(layout.id());
        w.wreck = true;
        w.placeAt(new Vec3d(getX(), getY(), getZ()), orientation());
        List<String> gone = new java.util.ArrayList<>();
        Vec3d centroid = Vec3d.ZERO;
        int n = 0;
        for (PartInstance p : layout.parts()) {
            if (keep.contains(p.slot())) {
                centroid = centroid.add(p.localPosition());
                n++;
            } else {
                gone.add(p.slot());
            }
        }
        for (var c : layout.cells()) gone.add(c.id()); // мешки рвутся при расколе — газ остаётся у корабля или уходит
        w.damage().destroy(gone);
        for (var b : w.flight.ballast()) w.flight.dropBallast(b.id, b.massKg()); // вода — в баках киля корабля
        w.flight.setAutoTrim(false);
        w.applyDamageState();
        Vec3d at = flight.body().localToWorld(centroid.scale(1.0 / Math.max(1, n)));
        w.flight.body().setVelocity(flight.body().velocityAt(at));
        w.flight.body().setAngularVelocity(flight.body().angularVelocity());
        w.syncDamage();
        w.syncCells(true);
        level().addFreshEntity(w);
        AirshipVoyage.LOGGER.info("Airship {}: wreck {} broke off", getUUID().toString().substring(0, 8), keep);
    }

    private void onDestroyed(List<String> lost, net.minecraft.world.phys.Vec3 from, boolean dropItems) {
        if (lost.isEmpty()) return;
        AirshipVoyage.LOGGER.info("Airship {}: lost {} (from {})", getUUID().toString().substring(0, 8), lost, from);
        spawnDebris(lost, from, dropItems, true);
        applyDamageState();
        syncDamage();
        syncCells(true);
        splitIfDisconnected();
    }

    /** Обломки: деталь — предметом, летящим от места удара; пыль и звук. Камера — без предмета (порвана). */
    private void spawnDebris(List<String> slots, net.minecraft.world.phys.Vec3 from, boolean dropItems, boolean shapesFromBase) {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel sl)) return;
        Vec3d pos = new Vec3d(getX(), getY(), getZ());
        var plan = AssemblyPlans.byLayoutId(layout.id()).orElse(null);
        for (String s : slots) {
            Vec3d c = null;
            for (PartInstance p : layout.parts()) if (p.slot().equals(s)) c = flight.body().localToWorld(p.localPosition());
            int ci = cellIndexOf(s);
            if (c == null && ci >= 0) c = flight.body().localToWorld(layout.cells().get(ci).bay().center());
            if (c == null) continue;
            var at = new net.minecraft.world.phys.Vec3(c.x(), c.y(), c.z());
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF, at.x, at.y, at.z, 12, 0.6, 0.4, 0.6, 0.05);
            sl.playSound(null, at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.ITEM_BREAK, net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 0.7f);
            boolean isCell = ci >= 0;
            if (!dropItems || plan == null || plan.step(s) == null || (isCell && shapesFromBase)) continue;
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(AirshipVoyage.MOD_ID, plan.step(s).itemKey()));
            if (item == net.minecraft.world.item.Items.AIR) continue;
            var drop = new net.minecraft.world.entity.item.ItemEntity(sl, at.x, at.y, at.z, new net.minecraft.world.item.ItemStack(item));
            net.minecraft.world.phys.Vec3 dir = from == null ? new net.minecraft.world.phys.Vec3(0, 1, 0) : at.subtract(from);
            dir = dir.lengthSqr() < 1e-6 ? new net.minecraft.world.phys.Vec3(0, 1, 0) : dir.normalize();
            double speed = from == null ? 0.1 : 0.45 + random.nextDouble() * 0.25;
            drop.setDeltaMovement(dir.x * speed, dir.y * speed + 0.25, dir.z * speed);
            drop.setDefaultPickUpDelay();
            sl.addFreshEntity(drop);
        }
    }

    /**
     * Взрыв рядом (ТЗ 18–19): каждой детали и камере — урон по расстоянию до ближайшей точки
     * ({@link HullDamage#blastDamage}), разрушенное разлетается; камера рвётся, водородная от сильного удара
     * загорается; кораблю — толчок 400·power·(1 − d/2power) Н·с в ближайшую к взрыву точку корпуса.
     */
    public void onExplosion(net.minecraft.world.phys.Vec3 centre, double power) {
        if (level().isClientSide || construction != null) return;
        Vec3d c = new Vec3d(centre.x, centre.y, centre.z);
        Vec3d pos = new Vec3d(getX(), getY(), getZ());
        double nearest = Double.MAX_VALUE;
        Vec3d nearestAt = null;
        java.util.Map<String, Double> hits = new java.util.LinkedHashMap<>();
        for (PartInstance p : visibleParts()) {
            for (OrientedBox b : p.hullShapes()) {
                OrientedBox w = b.transformed(pos, orientation());
                double d = w.distanceTo(c);
                hits.merge(p.slot(), HullDamage.blastDamage(power, d), Math::max);
                if (d < nearest) { nearest = d; nearestAt = w.center(); }
            }
        }
        for (int i = 0; i < layout.cells().size(); i++) {
            if (!cellPresent(i) || flight.cells().get(i).burning()) continue; // горящую жжёт пожар, не вспышка
            double d = layout.cells().get(i).bay().transformed(pos, orientation()).distanceTo(c);
            double dmg = HullDamage.blastDamage(power, d);
            if (dmg <= 0) continue;
            hits.merge(layout.cells().get(i).id(), dmg, Math::max);
            if (dmg >= 15) pendingIgnition.add(i); // пламя взрыва поджигает водород
        }
        List<String> lost = new java.util.ArrayList<>();
        for (var e : hits.entrySet()) {
            if (e.getValue() <= 0 || damage().isDestroyed(e.getKey())) continue;
            lost.addAll(damage().hit(e.getKey(), e.getValue()));
            int ci = cellIndexOf(e.getKey());
            if (ci >= 0 && !damage().isDestroyed(e.getKey())) {
                GasCell cell = flight.cells().get(ci);
                cell.setLeakPerSecond(cell.leakPerSecond() + e.getValue() / HullDamage.CELL_HP * 0.05);
            }
        }
        if (nearestAt != null) {
            double j = 400 * power * Math.max(0, 1 - nearest / (2 * power));
            Vec3d dir = nearestAt.sub(c);
            if (j > 0 && dir.length() > 1e-6) flight.body().applyImpulseAt(dir.normalized().scale(j), nearestAt);
        }
        onDestroyed(lost, centre, true);
        syncCells(true);
    }

    /**
     * Пожар (ТЗ 19), раз в тик на сервере: поджечь отложенные камеры — вспышка водорода (взрыв силы 2 + 2·наполнение
     * с огнём); горящая камера раз в секунду жжёт детали, касающиеся отсека, и с вероятностью 15 % поджигает соседнюю
     * водородную; выгоревшая — разрушена.
     */
    /** Урон огнём детали, касающейся горящего мешка, в секунду: ферма (20) прогорает за ≈ 13 с, кольцо (48) — нет. */
    private static final double FIRE_DAMAGE_PER_S = 1.5;

    private void tickFire() {
        if (!pendingIgnition.isEmpty()) {
            List<Integer> now = new java.util.ArrayList<>(pendingIgnition);
            pendingIgnition.clear();
            WorldEnvironment env = environment();
            for (int i : now) {
                if (i >= flight.cells().size() || !flight.igniteCell(i)) continue;
                Vec3d c = flight.body().localToWorld(layout.cells().get(i).bay().center());
                double power = 2 + 2 * Math.min(1, flight.fill(i, env));
                syncDamage();
                level().explode(this, c.x(), c.y(), c.z(), (float) power, true, Level.ExplosionInteraction.MOB);
            }
        }
        boolean changed = false;
        for (int i = 0; i < flight.cells().size(); i++) {
            GasCell cell = flight.cells().get(i);
            String id = layout.cells().get(i).id();
            if (cell.destroyed() && !damage().isDestroyed(id)) {
                onDestroyed(damage().destroy(List.of(id)), null, false); // выгорела
                changed = true;
                continue;
            }
            if (!cell.burning() || tickCount % 20 != 0) continue;
            Vec3d pos = new Vec3d(getX(), getY(), getZ());
            OrientedBox bay = layout.cells().get(i).bay().transformed(pos, orientation());
            igniteSurroundings(bay);
            if (level().isRainingAt(net.minecraft.core.BlockPos.containing(bay.center().x(), bay.center().y() + bay.halfExtents().y() + 1, bay.center().z()))
                    && random.nextDouble() < 0.1 && cell.extinguish()) {
                changed = true; // дождь потушил
                continue;
            }
            List<String> lost = new java.util.ArrayList<>();
            for (PartInstance p : visibleParts()) {
                for (OrientedBox b : p.hullShapes()) {
                    // деталь касается отсека: её центр не дальше 1,5 м от стенок горящего мешка
                    if (bay.distanceTo(b.transformed(pos, orientation()).center()) <= 1.5) {
                        lost.addAll(damage().hit(p.slot(), FIRE_DAMAGE_PER_S));
                        break;
                    }
                }
            }
            for (int k = 0; k < flight.cells().size(); k++) {
                if (k == i || !cellPresent(k) || flight.cells().get(k).burning()) continue;
                OrientedBox other = layout.cells().get(k).bay().transformed(pos, orientation());
                if (other.distanceTo(bay.center()) <= bay.halfExtents().length() + 1.5 && random.nextDouble() < 0.15) {
                    pendingIgnition.add(k);
                }
            }
            onDestroyed(lost, null, true);
        }
        if (changed || tickCount % 20 == 0 && anyBurning()) syncDamage();
    }

    /**
     * Огонь горящего мешка (D-153): кто внутри отсека или в 1,5 м от него — загорается на 4 с; под мешком на
     * ближайшей опоре (до 8 м вниз) с вероятностью 30 % в секунду появляется огонь, если там горит.
     */
    private void igniteSurroundings(OrientedBox bay) {
        Vec3d c = bay.center(), h = bay.halfExtents();
        AABB area = new AABB(c.x() - h.length() - 1.5, c.y() - h.length() - 1.5, c.z() - h.length() - 1.5,
                c.x() + h.length() + 1.5, c.y() + h.length() + 1.5, c.z() + h.length() + 1.5);
        for (Entity e : level().getEntities(this, area, x -> !(x instanceof AirshipEntity) && !x.fireImmune())) {
            if (bay.distanceTo(new Vec3d(e.getX(), e.getY() + e.getBbHeight() / 2, e.getZ())) <= 1.5) e.igniteForSeconds(4);
        }
        if (random.nextDouble() >= 0.3) return;
        // огонь на блоки — только где он вообще распространяется (правило doFireTick), как у ванильного огня
        if (!level().getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOFIRETICK)) return;
        double x = c.x() + (random.nextDouble() * 2 - 1) * h.x(), z = c.z() + (random.nextDouble() * 2 - 1) * h.z();
        int y0 = (int) Math.floor(c.y() - h.y());
        for (int y = y0; y > y0 - 8; y--) {
            var at = net.minecraft.core.BlockPos.containing(x, y, z);
            if (!level().getBlockState(at).isAir()) {
                var above = at.above();
                if (level().getBlockState(above).isAir()
                        && net.minecraft.world.level.block.BaseFireBlock.canBePlacedAt(level(), above, net.minecraft.core.Direction.UP)) {
                    level().setBlockAndUpdate(above, net.minecraft.world.level.block.BaseFireBlock.getState(level(), above));
                }
                return;
            }
        }
    }

    /** Пламя и дым над горящими камерами (клиент). */
    private void spawnFireParticles() {
        Vec3d pos = new Vec3d(getX(), getY(), getZ());
        for (int i = 0; i < cellBurning.length && i < layout.cells().size(); i++) {
            if (!cellBurning[i]) continue;
            OrientedBox bay = layout.cells().get(i).bay();
            Vec3d h = bay.halfExtents();
            for (int k = 0; k < 6; k++) {
                Vec3d local = bay.center().add(new Vec3d((random.nextDouble() * 2 - 1) * h.x(), (random.nextDouble() * 2 - 1) * h.y(),
                        (random.nextDouble() * 2 - 1) * h.z()));
                Vec3d w = pos.add(orientation().rotate(local));
                level().addParticle(net.minecraft.core.particles.ParticleTypes.FLAME, w.x(), w.y(), w.z(), 0, 0.05, 0);
            }
            Vec3d top = pos.add(orientation().rotate(bay.center().add(new Vec3d(0, h.y(), 0))));
            level().addParticle(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE, top.x(), top.y(), top.z(), 0, 0.08, 0);
        }
    }

    public void setLayoutById(String id) {
        ShipLayouts.byId(id).ifPresent(this::setLayout);
    }

    public ShipLayout layout() { return layout; }

    /** Модель полёта (сервер). */
    public FlightModel flight() { return flight; }

    /** Окружение для физики — блоки и атмосфера этого мира. */
    /**
     * Окружение корабля: одно на корабль (ревью: создавалось на каждый вызов, а ветер запрашивается на каждом
     * подшаге по каждому рулю и мотору). Пересоздаётся раз в 100 тиков — подхватить изменённые настройки высоты.
     */
    public WorldEnvironment environment() {
        if (env == null || env.level() != level() || tickCount - envTick >= 100) {
            env = new WorldEnvironment(level());
            envTick = tickCount;
        }
        return env;
    }

    private WorldEnvironment env;
    private int envTick;

    /** Наполнение камер для рендера (клиент) — 0..1 по порядку {@code layout().cells()}. */
    public float[] cellFillForRender() { return cellFill; }

    // ------------------------------------------------------------------ штурвал (AV-013) и люди на борту (AV-014)

    /** Стоящий у штурвала (сервер); не сохраняется — после перезагрузки к штурвалу встают заново. */
    private java.util.UUID helmsman;

    public java.util.UUID helmsman() { return helmsman; }

    /** Держит ли пост клапаны открытыми клавишей V (сервер, не сохраняется). */
    private boolean helmVent;

    public boolean helmVentCommand() { return helmVent; }

    public void setHelmVentCommand(boolean open) { helmVent = open; }

    public void setHelmsman(java.util.UUID id) {
        helmsman = id;
        entityData.set(DATA_HELM, id == null ? "" : id.toString());
        if (id != null) syncHud();
    }

    /** Стоит ли игрок у штурвала (на клиенте — по синхронизированным данным). */
    public boolean isHelmsman(net.minecraft.world.entity.player.Player p) {
        return level().isClientSide ? p.getUUID().toString().equals(entityData.get(DATA_HELM))
                : p.getUUID().equals(helmsman);
    }

    /** Данные приборов для клиента. */
    public CompoundTag hud() { return entityData.get(DATA_HUD); }

    private void syncHud() {
        if (level().isClientSide) return;
        CompoundTag t = new CompoundTag();
        var engines = flight.engines();
        t.putString("tg", engines.isEmpty() ? "" : engines.get(0).telegraph().key());
        t.putFloat("thr", engines.isEmpty() ? 0 : (float) engines.get(0).throttle());
        t.putFloat("r", (float) flight.rudderDeg());
        t.putFloat("e", (float) flight.elevatorDeg());
        Vec3d v = flight.body().velocity();
        t.putFloat("spd", (float) Math.hypot(v.x(), v.z()));
        t.putFloat("vs", (float) v.y());
        WorldEnvironment env = environment();
        t.putFloat("alt", (float) env.altitudeMeters(getY()));
        t.putFloat("h", (float) flight.balance(env).heavinessKg());
        double ballastKg = 0;
        for (FlightModel.Ballast b : flight.ballast()) ballastKg += b.massKg();
        t.putFloat("b", (float) ballastKg);
        var bow = flight.endBallast(true);
        var stern = flight.endBallast(false);
        t.putFloat("bf", bow == null ? 0 : (float) bow.massKg());
        t.putFloat("ba", stern == null ? 0 : (float) stern.massKg());
        t.putFloat("pitch", (float) Math.toDegrees(flight.body().orientation().pitchRad()));
        t.putBoolean("auto", flight.autoTrim());
        var ap = flight.autopilot();
        t.putBoolean("ap", ap.engaged());
        t.putFloat("apH", (float) ap.headingDeg());
        t.putFloat("apA", (float) ap.altitudeM());
        t.putFloat("hdg", (float) flight.headingDeg());
        t.putBoolean("moored", flight.mooringAnchor() != null);
        Vec3d wind = env.wind(new Vec3d(getX(), getY(), getZ()));
        t.putFloat("wx", (float) wind.x());
        t.putFloat("wz", (float) wind.z());
        t.putBoolean("vane", hasPart(com.airshipvoyage.core.assembly.AssemblyPlans.WEATHERVANE)); // прибор ветра (D-169)
        Vec3d air = flight.body().velocity().sub(wind);
        t.putFloat("air", (float) Math.hypot(air.x(), air.z()));
        // моторы: борт, фактическая доля мощности и куда она тянется (телеграф + поправка от руля)
        ListTag eng = new ListTag();
        for (var e : engines) {
            CompoundTag c = new CompoundTag();
            c.putBoolean("port", e.mount().thrustPoint().z() < 0);
            c.putFloat("t", (float) e.throttle());
            c.putFloat("g", (float) e.targetThrottle());
            c.putFloat("fuel", (float) e.fuelKg());
            c.putFloat("flow", (float) e.fuelFlowKgPerS());
            eng.add(c);
        }
        t.put("eng", eng);
        ListTag fills = new ListTag();
        for (int i = 0; i < cellFill.length; i++) fills.add(net.minecraft.nbt.FloatTag.valueOf(cellFill[i]));
        t.put("fills", fills);
        double ph = flight.pressureHeightMeters(env);
        t.putFloat("ph", Double.isNaN(ph) ? -1 : (float) ph);
        double people = 0;
        people += flight.crewMassKg();
        t.putFloat("p", (float) people);
        t.putBoolean("v", flight.venting());
        float fill = 0;
        for (int i = 0; i < cellFill.length; i++) fill += cellFill[i] / cellFill.length;
        t.putFloat("gas", fill);
        entityData.set(DATA_HUD, t);
    }

    /**
     * Масса людей и мобов на борту (ТЗ 8.3, AV-014): кто стоит на деталях этого корабля (под ступнями — деталь),
     * тот добавляет массу в точке своих ступней. Игрок — 80 кг, остальные — по объёму коробки относительно игрока.
     * На сервере игрок кораблями не обрезается (см. ShipCollisions), поэтому опора проверяется здесь напрямую.
     */
    /** @return есть ли на борту игрок (экипаж — для удержания чанков, ТЗ 22.9) */
    private boolean updatePayload() {
        boolean crew = false;
        Vec3d pos = new Vec3d(getX(), getY(), getZ());
        var ship = new HullCollider.Ship(pos, flight.body().orientation(), hullShapes());
        List<FlightModel.Payload> people = new java.util.ArrayList<>();
        for (Entity e : level().getEntities(this, getBoundingBox().inflate(0.5, 2.5, 0.5),
                e -> e instanceof net.minecraft.world.entity.LivingEntity && e.isAlive() && !e.isSpectator())) {
            AABB bb = e.getBoundingBox();
            Vec3d lo = new Vec3d(bb.minX + 0.05, bb.minY - 0.15, bb.minZ + 0.05);
            Vec3d hi = new Vec3d(bb.maxX - 0.05, bb.minY + 0.05, bb.maxZ - 0.05);
            if (!HullCollider.supports(lo, hi, List.of(ship))) continue;
            people.add(new FlightModel.Payload(flight.body().worldToLocal(new Vec3d(e.getX(), e.getY(), e.getZ())), massOf(e)));
            if (e instanceof net.minecraft.world.entity.player.Player) crew = true;
        }
        double kg = 0;
        for (FlightModel.Payload p : people) kg += p.massKg();
        flight.setCrewMass(kg); // в центр масс корабля, без наклона (D-141)
        return crew;
    }

    // ------------------------------------------------------------------ чанки и реестр (ТЗ 22.9, AV-017)

    private final com.airshipvoyage.core.world.ChunkKeeping keeping = new com.airshipvoyage.core.world.ChunkKeeping();
    private java.util.Set<Long> heldChunks = java.util.Set.of();
    private int pausedTicks;

    public com.airshipvoyage.core.world.ChunkKeeping chunkKeeping() { return keeping; }

    /** Чанки, которые корабль сейчас держит загруженными (сервер). */
    public java.util.Set<Long> heldChunks() { return java.util.Collections.unmodifiableSet(heldChunks); }

    /** Сколько тиков подряд физика стоит на паузе: часть корпуса над незагруженным чанком. */
    public int pausedTicks() { return pausedTicks; }

    private Aabb worldBox() {
        Vec3d[] b = flight.layout().worldAabb(flight.body().position(), flight.body().orientation());
        return new Aabb(b[0], b[1]);
    }

    /** Земля под всем корпусом загружена: иначе контакт считался бы с «пустотой» (ТЗ 22.9). */
    private boolean footprintLoaded() {
        for (long k : com.airshipvoyage.core.world.ChunkKeeping.footprint(worldBox(), Vec3d.ZERO, 1.0, 0)) {
            if (!level().hasChunk(com.airshipvoyage.core.world.ChunkKeeping.chunkX(k), com.airshipvoyage.core.world.ChunkKeeping.chunkZ(k))) return false;
        }
        return true;
    }

    private void updateChunks(boolean crewAboard, int ticks) {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel sl)) return;
        boolean hold = keeping.tick(crewAboard, ticks) && construction == null;
        java.util.Set<Long> want = hold ? com.airshipvoyage.core.world.ChunkKeeping.footprint(worldBox(), flight.body().velocity(),
                com.airshipvoyage.core.world.ChunkKeeping.MARGIN, com.airshipvoyage.core.world.ChunkKeeping.LOOKAHEAD_S) : java.util.Set.of();
        if (!want.equals(heldChunks)) heldChunks = com.airshipvoyage.world.ShipChunks.update(sl, getUUID(), heldChunks, want);
    }

    private void releaseChunks() {
        if (level() instanceof net.minecraft.server.level.ServerLevel sl && !heldChunks.isEmpty()) {
            heldChunks = com.airshipvoyage.world.ShipChunks.update(sl, getUUID(), heldChunks, java.util.Set.of());
        }
    }

    private void updateRecord() {
        if (level() instanceof net.minecraft.server.level.ServerLevel sl) {
            com.airshipvoyage.world.ShipRecords.get(sl).put(new com.airshipvoyage.world.ShipRecords.Entry(getUUID(),
                    flight.layout().id(), Math.round(getX() * 10) / 10.0, Math.round(getY() * 10) / 10.0,
                    Math.round(getZ() * 10) / 10.0, sl.getGameTime(), construction != null, wreck));
        }
    }

    /** Масса существа на борту, кг: игрок — 80, остальные — пропорционально объёму коробки (игрок 0,6 × 1,8 × 0,6). */
    public static double massOf(Entity e) {
        if (e instanceof net.minecraft.world.entity.player.Player) return 80;
        AABB bb = e.getBoundingBox();
        double volume = bb.getXsize() * bb.getYsize() * bb.getZsize();
        return Math.max(5, Math.min(3000, 80 * volume / 0.648));
    }

    // --- моторы: на сервере — последние отправленные доли мощности, на клиенте — полученные и углы винтов ---
    private float[] engineThrottle = new float[0];
    private float[] propAngle = new float[0], propAngleO = new float[0];
    /** Отклонение рулей: на сервере — последнее отправленное, на клиенте — цель и показываемое (градусы). */
    private float rudderShown, elevatorShown, rudderTarget, elevatorTarget;
    private float rudderShownO, elevatorShownO;

    /** Отклонение руля направления для рендера, градусы (&gt; 0 — влево). */
    public float rudderForRender(float partialTick) { return rudderShownO + (rudderShown - rudderShownO) * partialTick; }

    /** Отклонение руля высоты для рендера, градусы (&gt; 0 — нос вверх). */
    public float elevatorForRender(float partialTick) { return elevatorShownO + (elevatorShown - elevatorShownO) * partialTick; }

    /** Угол винта мотора {@code index} (по {@code layout().engines()}) для рендера с учётом доли тика. */
    public float propAngle(int index, float partialTick) {
        if (index >= propAngle.length) return 0;
        return propAngleO[index] + (propAngle[index] - propAngleO[index]) * partialTick;
    }

    /** Отправить клиентам доли мощности моторов, если заметно изменились (для винтов). */
    private void syncEngines(boolean force) {
        if (level().isClientSide) return;
        var engines = flight.engines();
        if (engineThrottle.length != engines.size()) {
            engineThrottle = new float[engines.size()];
            force = true;
        }
        boolean changed = force;
        for (int i = 0; i < engines.size(); i++) {
            if (Math.abs(engines.get(i).throttle() - engineThrottle[i]) > 0.01) changed = true;
        }
        // отклонение рулей — тем же пакетом (видно, как руль ходит, ТЗ 8.5); порог 0,5°
        if (Math.abs(flight.rudderDeg() - rudderShown) > 0.5 || Math.abs(flight.elevatorDeg() - elevatorShown) > 0.5
                || (flight.rudderDeg() != rudderShown && flight.rudderDeg() == flight.rudderCommandDeg())
                || (flight.elevatorDeg() != elevatorShown && flight.elevatorDeg() == flight.elevatorCommandDeg())) changed = true;
        if (!changed) return;
        ListTag list = new ListTag();
        for (int i = 0; i < engines.size(); i++) {
            engineThrottle[i] = (float) engines.get(i).throttle();
            list.add(net.minecraft.nbt.FloatTag.valueOf(engineThrottle[i]));
        }
        CompoundTag tag = new CompoundTag();
        tag.put("t", list);
        rudderShown = (float) flight.rudderDeg();
        elevatorShown = (float) flight.elevatorDeg();
        tag.putFloat("r", rudderShown);
        tag.putFloat("e", elevatorShown);
        entityData.set(DATA_ENGINES, tag);
    }
    public boolean[] cellLeakingForRender() { return cellLeaking; }

    /**
     * Отправить состояние камер клиентам, если оно заметно изменилось (или принудительно).
     * Частые мелкие изменения не шлём — мешок на глаз не меняется от 0,2 % наполнения.
     */
    public void syncCells(boolean force) {
        if (level().isClientSide || layout.cells().isEmpty()) return;
        WorldEnvironment env = environment();
        // На сервере cellFill/cellLeaking — последнее ОТПРАВЛЕННОЕ состояние. Сравниваем с ним, а не с прошлым
        // замером: иначе медленная утечка (за 5 тиков меньше порога) не отправлялась бы никогда (ревью 2, №6).
        int n = cellFill.length;
        float[] fill = new float[n];
        boolean[] leaking = new boolean[n];
        boolean changed = force;
        for (int i = 0; i < n; i++) {
            fill[i] = (float) flight.fill(i, env);
            leaking[i] = flight.cells().get(i).leakPerSecond() > 0;
            if (Math.abs(fill[i] - cellFill[i]) > 0.002f || leaking[i] != cellLeaking[i]) changed = true;
        }
        if (!changed) return;
        System.arraycopy(fill, 0, cellFill, 0, n);
        System.arraycopy(leaking, 0, cellLeaking, 0, n);
        CompoundTag tag = new CompoundTag();
        ListTag fills = new ListTag(), leaks = new ListTag();
        for (int i = 0; i < cellFill.length; i++) {
            fills.add(net.minecraft.nbt.FloatTag.valueOf(cellFill[i]));
            leaks.add(net.minecraft.nbt.ByteTag.valueOf(cellLeaking[i]));
        }
        tag.put("fill", fills);
        tag.put("leak", leaks);
        entityData.set(DATA_CELLS, tag);
    }

    /** Переставить корабль (сервер): позиция начала локальных координат и ориентация, коробка — сразу. */
    public void placeAt(Vec3d position, Quat orientation) {
        flight.place(position, orientation);
        setPos(position.x(), position.y(), position.z());
        syncOrientation();
    }

    /** Сменить ориентацию (сервер), сохранив вращение; коробка и клиенты обновляются сразу. */
    public void setOrientation(Quat orientation) {
        flight.body().setOrientation(orientation);
        reapplyPosition();
        syncOrientation();
    }

    /** yRot/xRot в соглашениях Minecraft (yRot 0 — на юг, xRot &gt; 0 — вниз) — для F3, команд и других модов. */
    private void applyVanillaAngles(Quat q) {
        setYRot(vanillaYawDegrees(q));
        setXRot(vanillaPitchDegrees(q));
    }

    /** Курс корпуса (0 — нос на восток, &gt; 0 — к северу) → yRot Minecraft. */
    public static float vanillaYawDegrees(Quat q) {
        return net.minecraft.util.Mth.wrapDegrees((float) Math.toDegrees(-q.yawRad()) - 90f);
    }

    public static float vanillaPitchDegrees(Quat q) {
        return (float) Math.toDegrees(-q.pitchRad());
    }

    private void syncOrientation() {
        Quat q = flight.body().orientation();
        entityData.set(DATA_ORIENTATION, new Quaternionf((float) q.x(), (float) q.y(), (float) q.z(), (float) q.w()));
    }

    /** Серверное твёрдое тело (на клиенте — заглушка без движения). */
    public RigidBody body() { return flight.body(); }

    /** Ориентация корпуса: на сервере — из физики, на клиенте — последняя интерполированная. */
    public Quat orientation() {
        return level().isClientSide || flight == null ? orientation : flight.body().orientation();
    }

    /** Ориентация для рендера с учётом доли тика. */
    public Quat renderOrientation(float partialTick) {
        return orientationO.slerp(orientation, partialTick);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (!level().isClientSide) return;
        if (DATA_LAYOUT.equals(key)) {
            ShipLayouts.byId(entityData.get(DATA_LAYOUT)).ifPresent(l -> {
                if (l != this.layout) {
                    this.layout = l;
                    cellFill = new float[l.cells().size()];
                    cellLeaking = new boolean[l.cells().size()];
                    // повреждения считались по прежнему чертежу (ревью: клиент видел и задевал «призрачные» детали)
                    damage = null;
                    intact = null;
                    cellGone = new boolean[l.cells().size()];
                    cellBurning = new boolean[l.cells().size()];
                    applyDamageTag();
                }
                applyConstructionTag();
                reapplyPosition();
            });
        } else if (DATA_CONSTRUCTION.equals(key)) {
            applyConstructionTag();
        } else if (DATA_DAMAGE.equals(key)) {
            applyDamageTag();
        } else if (DATA_ENGINES.equals(key)) {
            CompoundTag et = entityData.get(DATA_ENGINES);
            ListTag t = et.getList("t", Tag.TAG_FLOAT);
            engineThrottle = new float[t.size()];
            for (int i = 0; i < t.size(); i++) engineThrottle[i] = t.getFloat(i);
            rudderTarget = et.getFloat("r");
            elevatorTarget = et.getFloat("e");
        } else if (DATA_CELLS.equals(key)) {
            CompoundTag tag = entityData.get(DATA_CELLS);
            ListTag fills = tag.getList("fill", Tag.TAG_FLOAT), leaks = tag.getList("leak", Tag.TAG_BYTE);
            int n = Math.min(fills.size(), cellFill.length);
            for (int i = 0; i < n; i++) {
                cellFill[i] = fills.getFloat(i);
                cellLeaking[i] = i < leaks.size() && ((net.minecraft.nbt.ByteTag) leaks.get(i)).getAsByte() != 0;
            }
        } else if (DATA_ORIENTATION.equals(key)) {
            Quaternionf q = entityData.get(DATA_ORIENTATION);
            orientationTarget = new Quat(q.w, q.x, q.y, q.z).normalized();
            if (tickCount == 0) {
                orientation = orientationO = orientationTarget;
                reapplyPosition();
            } else {
                orientationLerpSteps = 3;
            }
        }
    }

    /** Клиент: восстановить стройку из синхронизированных данных (план — по текущему типу корабля). */
    private void applyConstructionTag() {
        CompoundTag tag = entityData.get(DATA_CONSTRUCTION);
        if (!tag.getBoolean("active") || layout == null) {
            construction = null;
            constructionShapes = List.of();
            return;
        }
        AssemblyPlans.byLayoutId(layout.id()).ifPresentOrElse(plan -> {
            construction = ConstructionState.restore(plan, readStrings(tag, "installed"));
            constructionShapes = List.copyOf(construction.installedShapes());
        }, () -> construction = null);
    }

    private static ListTag strings(java.util.Collection<String> values) {
        ListTag list = new ListTag();
        for (String s : values) list.add(net.minecraft.nbt.StringTag.valueOf(s));
        return list;
    }

    private static List<String> readStrings(CompoundTag tag, String key) {
        ListTag list = tag.getList(key, Tag.TAG_STRING);
        List<String> out = new java.util.ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) out.add(list.getString(i));
        return out;
    }

    // ------------------------------------------------------------------ тик

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            tickClient();
        } else {
            tickServer();
        }
    }

    private void tickServer() {
        RigidBody body = flight.body();
        if (tickCount % 100 == 0) updateRecord();
        if (tickCount % 10 == 0) syncWind();
        tickFire();
        // На случай, если позицию поменял кто-то извне (/tp, загрузка мира).
        Vec3d entityPos = new Vec3d(getX(), getY(), getZ());
        if (entityPos.distanceTo(body.position()) > 1e-6) body.setPosition(entityPos);
        Quat oldRot = body.orientation();
        if (construction != null) {
            // на стапеле корабль неподвижен: физика полёта не считается (ТЗ 5.4 — спуск только после постройки)
            flight.tickGas(DT, environment());
            if (tickCount % 5 == 0) syncCells(false);
            pushOutEntities(entityPos, oldRot); // как на клиенте: застрявших в деталях стапеля — наружу (ревью)
            body.freeze();
            setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            return;
        }

        if (!footprintLoaded()) {
            // часть корпуса над незагруженным чанком: пауза, скорость сохраняется (ТЗ 22.9)
            pausedTicks++;
            setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            return;
        }
        pausedTicks = 0;
        try {
            flight.step(DT, environment());
            applyImpacts(flight.drainImpacts());
        } catch (IllegalStateException diverged) {
            AirshipVoyage.LOGGER.error("Airship {} physics diverged, freezing", getUUID(), diverged);
            body.freeze();
            body.setOrientation(Quat.IDENTITY);
            body.setPosition(entityPos);
        }
        // Ниже мира — корабль потерян (как и любая сущность).
        if (body.position().y() < level().getMinBuildHeight() - 64) {
            discard();
            return;
        }

        Vec3d p = body.position();
        setPos(p.x(), p.y(), p.z());
        Vec3d v = body.velocity();
        setDeltaMovement(v.x() * DT, v.y() * DT, v.z() * DT);
        applyVanillaAngles(body.orientation());
        syncOrientation();
        if (tickCount % 5 == 0) syncCells(false);
        syncEngines(false);
        if (tickCount % 10 == 0) updateChunks(updatePayload() || helmsman != null, 10);
        if (helmsman != null) {
            var pilot = level().getPlayerByUUID(helmsman);
            if (pilot == null || !pilot.isAlive() || !com.airshipvoyage.control.HelmService.nearHelm(pilot, this)) {
                com.airshipvoyage.control.HelmService.release(this);
            } else if (tickCount % 4 == 0) {
                syncHud();
            }
        }
        carryStandingEntities(entityPos, oldRot, p, body.orientation());
        pushOutEntities(entityPos, oldRot);
    }

    /** Ветер для флюгера: раз в полсекунды и только если заметно изменился (поворот ветра — градусы в секунду). */
    private void syncWind() {
        Vec3d w = environment().wind(new Vec3d(getX(), getY(), getZ()));
        org.joml.Vector3f old = entityData.get(DATA_WIND);
        if (Math.abs(old.x() - w.x()) + Math.abs(old.z() - w.z()) > 0.05) {
            entityData.set(DATA_WIND, new org.joml.Vector3f((float) w.x(), 0, (float) w.z()));
        }
    }

    private void tickClient() {
        Vec3d oldPos = new Vec3d(getX(), getY(), getZ());
        spawnFireParticles();
        // руль доходит до присланного значения со скоростью рулевой машинки (15°/с = 0,75° за тик)
        rudderShownO = rudderShown;
        elevatorShownO = elevatorShown;
        float step = (float) (FlightModel.SERVO_DEG_PER_S * DT);
        rudderShown += Math.max(-step, Math.min(step, rudderTarget - rudderShown));
        elevatorShown += Math.max(-step, Math.min(step, elevatorTarget - elevatorShown));
        Quat oldRot = orientation;
        orientationO = orientation;
        if (propAngle.length != engineThrottle.length) {
            propAngle = new float[engineThrottle.length];
            propAngleO = new float[engineThrottle.length];
        }
        for (int i = 0; i < propAngle.length; i++) {
            propAngleO[i] = propAngle[i];
            propAngle[i] += engineThrottle[i] * PROP_RAD_PER_TICK_FULL;
        }
        if (orientationLerpSteps > 0) {
            orientation = orientation.slerp(orientationTarget, 1.0 / orientationLerpSteps);
            orientationLerpSteps--;
        }
        if (lerpSteps > 0) {
            lerpPositionAndRotationStep(lerpSteps, lerpX, lerpY, lerpZ, getYRot(), getXRot());
            lerpSteps--;
        }
        // Стандартные углы сущности — из того же кватерниона, что и рендер (ревью №3):
        // углы из пакетов движения не используем, чтобы они не расходились с видимым корпусом.
        applyVanillaAngles(orientation);
        // Коробка зависит от ориентации — пересчитываем каждый тик.
        reapplyPosition();
        carryStandingEntities(oldPos, oldRot, new Vec3d(getX(), getY(), getZ()), orientation);
        pushOutEntities(oldPos, oldRot);
    }

    // ------------------------------------------------------------------ перенос стоящих на борту (ТЗ, 12.3)

    /**
     * Сдвинуть и повернуть вместе с кораблём всех, кто стоит на нём (или только что подпрыгнул внутри него).
     * На клиенте это свой игрок, на сервере — мобы и предметы (см. {@link ShipCollisions}).
     * Сошедший с корабля получает скорость точки корпуса, с которой ушёл, — корабль не «уезжает из-под ног».
     */
    private void carryStandingEntities(Vec3d oldPos, Quat oldRot, Vec3d newPos, Quat newRot) {
        if (layout == null) return;
        long now = level().getGameTime();
        float yawDelta = net.minecraft.util.Mth.wrapDegrees(vanillaYawDegrees(newRot) - vanillaYawDegrees(oldRot));

        for (var entry : ShipCollisions.contactsOf(this)) {
            Entity e = entry.getKey();
            ShipCollisions.Contact c = entry.getValue();
            if (e.isRemoved() || e.level() != level() || !ShipCollisions.participates(e)) {
                ShipCollisions.clearContact(e);
                continue;
            }
            Vec3d foot = new Vec3d(e.getX(), e.getY(), e.getZ());
            boolean standing = now - c.lastLandedTick <= 1;
            boolean airborneInside = now - c.lastLandedTick <= ShipCollisions.AIRBORNE_GRACE_TICKS
                    && insideHullVolume(oldRot.inverseRotate(foot.sub(oldPos)));
            if (!standing && !airborneInside) {
                // сошёл или спрыгнул за борт — дальше летит сам, сохранив скорость палубы
                e.setDeltaMovement(e.getDeltaMovement().add(c.lastCarry));
                ShipCollisions.clearContact(e);
                continue;
            }
            Vec3d moved = HullCollider.carry(foot, oldPos, oldRot, newPos, newRot);
            // Ревью 2, №2: перенос — тоже движение, и блоки мира его останавливают (низкий потолок над палубой,
            // стена сбоку). Сам корабль здесь не учитывается — иначе несущая палуба мешала бы своему пассажиру.
            // Остановленного блоком палуба «обгоняет»: он теряет опору и дальше движется сам.
            net.minecraft.world.phys.Vec3 want = new net.minecraft.world.phys.Vec3(
                    moved.x() - foot.x(), moved.y() - foot.y(), moved.z() - foot.z());
            net.minecraft.world.phys.Vec3 allowed = Entity.collideBoundingBox(e, want, e.getBoundingBox(), level(), List.of());
            c.lastCarry = allowed;
            e.setPos(foot.x() + allowed.x, foot.y() + allowed.y, foot.z() + allowed.z);
            if (yawDelta != 0) {
                e.setYRot(e.getYRot() + yawDelta);
                if (e instanceof net.minecraft.world.entity.LivingEntity living) {
                    living.setYHeadRot(living.getYHeadRot() + yawDelta);
                    living.yBodyRot += yawDelta;
                }
            }
        }
    }

    /** Точка (локальные координаты) внутри габарита корабля с запасом на прыжок над верхней палубой. */
    private boolean insideHullVolume(Vec3d local) {
        Vec3d lo = layout.localBoundsMin(), hi = layout.localBoundsMax();
        return local.x() >= lo.x() - 0.5 && local.x() <= hi.x() + 0.5
                && local.z() >= lo.z() - 0.5 && local.z() <= hi.z() + 0.5
                && local.y() >= lo.y() - 1.0 && local.y() <= hi.y() + 3.0;
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        ShipRegistry.add(this);
        updateRecord();
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        ShipRegistry.remove(this);
        if (!level().isClientSide) {
            releaseChunks();
            RemovalReason why = getRemovalReason();
            if (why != null && (why.shouldDestroy() || why == RemovalReason.CHANGED_DIMENSION)) {
                com.airshipvoyage.world.ShipRecords.get((net.minecraft.server.level.ServerLevel) level()).remove(getUUID());
            } else {
                updateRecord();
            }
        }
        for (var entry : ShipCollisions.contactsOf(this)) ShipCollisions.clearContact(entry.getKey());
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        lerpX = x;
        lerpY = y;
        lerpZ = z;
        lerpSteps = Math.max(steps, 3);
    }

    @Override public double lerpTargetX() { return lerpSteps > 0 ? lerpX : getX(); }
    @Override public double lerpTargetY() { return lerpSteps > 0 ? lerpY : getY(); }
    @Override public double lerpTargetZ() { return lerpSteps > 0 ? lerpZ : getZ(); }

    // ------------------------------------------------------------------ габариты

    /** Описанный AABB всего каркаса в текущей ориентации. Конструктор Entity зовёт это до инициализации полей. */
    @Override
    protected AABB makeBoundingBox() {
        if (layout == null) return super.makeBoundingBox();
        Vec3d[] box = layout.worldAabb(new Vec3d(getX(), getY(), getZ()), orientation());
        return new AABB(box[0].x(), box[0].y(), box[0].z(), box[1].x(), box[1].y(), box[1].z());
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSqr) {
        double r = 256.0 * getViewScale();
        return distanceSqr < r * r;
    }

    // ------------------------------------------------------------------ взаимодействие

    /** Описанный AABB как коллизия не годится — у каркаса внутри пустота. Столкновения — по деталям, см. ShipCollisions. */
    @Override public boolean canBeCollidedWith() { return false; }
    /** Иначе огромная рамка перехватывала бы клики по блокам внутри себя. */
    @Override public boolean isPickable() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public void push(Entity entity) {}
    @Override public boolean hurt(DamageSource source, float amount) { return false; }
    @Override public boolean fireImmune() { return true; }
    @Override protected double getDefaultGravity() { return 0.0; }

    // ------------------------------------------------------------------ сохранение (ТЗ, 22.3, 22.9)

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Layout", Tag.TAG_STRING)) setLayoutById(tag.getString("Layout"));
        RigidBody body = flight.body();
        body.setPosition(new Vec3d(getX(), getY(), getZ()));
        body.setVelocity(Vec3d.ZERO);
        body.setAngularVelocity(Vec3d.ZERO);
        // Ревью 2, №3: сначала газ и балласт — окончательная масса и центр масс, и только потом скорости.
        // Пересборка массы при вращении добавляет ω × ΔЦМ (так и надо в полёте), но сохранённая скорость уже
        // относится к окончательному центру масс — при загрузке эта поправка была бы лишней.
        readFlightState(tag);
        if (tag.contains("Orientation", Tag.TAG_LIST)) {
            ListTag l = tag.getList("Orientation", Tag.TAG_DOUBLE);
            if (l.size() == 4) {
                body.setOrientation(new Quat(l.getDouble(0), l.getDouble(1), l.getDouble(2), l.getDouble(3)));
            }
        }
        body.setVelocity(readVec(tag, "Velocity"));
        body.setAngularVelocity(readVec(tag, "AngularVelocity"));
        // стройка (ТЗ, 4.4): установленные детали остаются на местах после перезапуска мира
        if (tag.contains("Construction", Tag.TAG_LIST)) {
            List<String> installed = readStrings(tag, "Construction");
            AssemblyPlans.byLayoutId(layout.id()).ifPresentOrElse(
                    plan -> setConstruction(ConstructionState.restore(plan, installed)),
                    () -> setConstruction(null));
        } else if (construction != null) {
            setConstruction(null);
        }
        wreck = tag.getBoolean("Wreck");
        // повреждения (AV-030): после газа — разрушенные камеры опустошаются, горящие горят дальше
        if (tag.contains("Damage", Tag.TAG_COMPOUND)) {
            CompoundTag d = tag.getCompound("Damage");
            java.util.Map<String, Double> taken = new java.util.HashMap<>();
            CompoundTag t = d.getCompound("taken");
            for (String k : t.getAllKeys()) taken.put(k, t.getDouble(k));
            damage().restore(taken, readStrings(d, "gone"));
            for (int i : d.getIntArray("burn")) {
                if (i >= 0 && i < flight.cells().size()) flight.cells().get(i).restoreState(false, true);
            }
        } else {
            damage = null;
        }
        applyDamageState();
        reapplyPosition();
        // Ревью №2: данные для клиентов должны совпадать с физикой сразу после загрузки,
        // а не после первого тика — корабль в неактивной зоне может долго не получать тиков.
        if (!level().isClientSide) {
            applyVanillaAngles(body.orientation());
            syncOrientation();
            syncCells(true);
            syncEngines(true);
        }
    }

    /** Газ, утечки, балласт, режим отладки (ТЗ, 22.3 — «состояние газовых камер»). */
    private void readFlightState(CompoundTag tag) {
        flight.setNeutral(tag.getBoolean("Neutral"));
        ListTag cells = tag.getList("Cells", Tag.TAG_COMPOUND);
        for (int i = 0; i < cells.size(); i++) {
            CompoundTag c = cells.getCompound(i);
            int idx = flight.cellIndex(c.getString("Id"));
            if (idx < 0) continue;
            GasCell cell = flight.cells().get(idx);
            try {
                LiftingGas gas = LiftingGas.valueOf(c.getString("Gas"));
                if (gas != cell.gas()) {
                    cell.setAmountMol(0);
                    cell.setGas(gas);
                }
            } catch (IllegalArgumentException ignored) {
                // неизвестный газ — оставляем по умолчанию
            }
            cell.setAmountMol(c.getDouble("Mol"));
            cell.setLeakPerSecond(c.getDouble("Leak"));
            cell.setManualValveOpen(c.getBoolean("ManualValve"));
        }
        // балласт — абсолютной массой (аудит 3, AUD-03-02): начальное распределение нового корабля
        // не должно ограничивать восстановление сохранённого
        ListTag ballast = tag.getList("Ballast", Tag.TAG_COMPOUND);
        for (int i = 0; i < ballast.size(); i++) {
            CompoundTag b = ballast.getCompound(i);
            flight.restoreBallast(b.getString("Id"), b.getDouble("Kg"));
        }
        // моторы (ТЗ 8.4): команда телеграфа и фактические обороты
        ListTag engines = tag.getList("Engines", Tag.TAG_COMPOUND);
        for (int i = 0; i < engines.size(); i++) {
            CompoundTag c = engines.getCompound(i);
            var e = flight.engine(c.getString("Id"));
            if (e == null) continue;
            com.airshipvoyage.core.propulsion.Telegraph.byKey(c.getString("Telegraph")).ifPresent(e::setTelegraph);
            e.setThrottle(c.getDouble("Throttle"));
            if (c.contains("Fuel")) e.setFuelKg(c.getDouble("Fuel")); // старые сохранения — полный бак
        }
        flight.massChanged();
        if (tag.contains("Autopilot", Tag.TAG_COMPOUND)) {
            CompoundTag a = tag.getCompound("Autopilot");
            flight.autopilot().restore(true, a.getDouble("Heading"), a.getDouble("Altitude"));
        } else {
            flight.autopilot().disengage();
        }
        ListTag mo = tag.getList("Mooring", Tag.TAG_DOUBLE);
        if (mo.size() == 4) flight.restoreMooring(new Vec3d(mo.getDouble(0), mo.getDouble(1), mo.getDouble(2)), mo.getDouble(3));
        else flight.unmoor();
        syncMooring();
        flight.setAutoTrim(!tag.contains("AutoTrim") || tag.getBoolean("AutoTrim")); // старые сохранения — включён
        ListTag rudders = tag.getList("Rudders", Tag.TAG_DOUBLE);
        if (rudders.size() == 4) {
            flight.restoreRudders(rudders.getDouble(0), rudders.getDouble(1), rudders.getDouble(2), rudders.getDouble(3));
        }
        flight.massChanged();
    }

    /** Наполнение камеры {@code index}, которое сейчас видят клиенты (значение синхронизируемых данных), −1 — нет. */
    public float syncedCellFill(int index) {
        ListTag fills = entityData.get(DATA_CELLS).getList("fill", Tag.TAG_FLOAT);
        return index < fills.size() ? fills.getFloat(index) : -1;
    }

    /** Ориентация, которую сейчас видят клиенты (значение синхронизируемых данных). */
    public Quat syncedOrientation() {
        Quaternionf q = entityData.get(DATA_ORIENTATION);
        return new Quat(q.w, q.x, q.y, q.z);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        RigidBody body = flight.body();
        tag.putString("Layout", layout.id());
        if (construction != null) tag.put("Construction", strings(construction.installed()));
        if (wreck) tag.putBoolean("Wreck", true);
        if (damage != null && (!damage.destroyed().isEmpty() || !damage.damageTaken().isEmpty() || anyBurning())) {
            CompoundTag d = new CompoundTag();
            CompoundTag taken = new CompoundTag();
            damage.damageTaken().forEach(taken::putDouble);
            d.put("taken", taken);
            d.put("gone", strings(damage.destroyed()));
            d.putIntArray("burn", burningIndices());
            tag.put("Damage", d);
        }
        tag.putBoolean("Neutral", flight.neutral());
        ListTag cells = new ListTag();
        for (GasCell cell : flight.cells()) {
            CompoundTag c = new CompoundTag();
            c.putString("Id", cell.id());
            c.putString("Gas", cell.gas().name());
            c.putDouble("Mol", cell.amountMol());
            c.putDouble("Leak", cell.leakPerSecond());
            c.putBoolean("ManualValve", cell.manualValveOpen());
            cells.add(c);
        }
        tag.put("Cells", cells);
        ListTag ballast = new ListTag();
        for (FlightModel.Ballast b : flight.ballast()) {
            CompoundTag c = new CompoundTag();
            c.putString("Id", b.id);
            c.putDouble("Kg", b.massKg());
            ballast.add(c);
        }
        tag.put("Ballast", ballast);
        tag.putBoolean("AutoTrim", flight.autoTrim());
        ListTag engines = new ListTag();
        for (var e : flight.engines()) {
            CompoundTag c = new CompoundTag();
            c.putString("Id", e.id());
            c.putString("Telegraph", e.telegraph().key());
            c.putDouble("Throttle", e.throttle());
            c.putDouble("Fuel", e.fuelKg());
            engines.add(c);
        }
        tag.put("Engines", engines);
        var ap = flight.autopilot();
        if (ap.engaged()) {
            CompoundTag a = new CompoundTag();
            a.putDouble("Heading", ap.headingDeg());
            a.putDouble("Altitude", ap.altitudeM());
            tag.put("Autopilot", a);
        }
        if (flight.mooringAnchor() != null) {
            Vec3d m = flight.mooringAnchor();
            tag.put("Mooring", doubles(m.x(), m.y(), m.z(), flight.ropeLength()));
        }
        // рули (ТЗ 8.5): команды и фактические отклонения
        tag.put("Rudders", doubles(flight.rudderCommandDeg(), flight.rudderDeg(),
                flight.elevatorCommandDeg(), flight.elevatorDeg()));
        Quat q = body.orientation();
        tag.put("Orientation", doubles(q.w(), q.x(), q.y(), q.z()));
        Vec3d v = body.velocity(), w = body.angularVelocity();
        tag.put("Velocity", doubles(v.x(), v.y(), v.z()));
        tag.put("AngularVelocity", doubles(w.x(), w.y(), w.z()));
    }

    private static ListTag doubles(double... values) {
        ListTag list = new ListTag();
        for (double d : values) list.add(DoubleTag.valueOf(d));
        return list;
    }

    private static Vec3d readVec(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LIST)) return Vec3d.ZERO;
        ListTag l = tag.getList(key, Tag.TAG_DOUBLE);
        if (l.size() != 3) return Vec3d.ZERO;
        Vec3d v = new Vec3d(l.getDouble(0), l.getDouble(1), l.getDouble(2));
        return v.isFinite() ? v : Vec3d.ZERO;
    }
}
