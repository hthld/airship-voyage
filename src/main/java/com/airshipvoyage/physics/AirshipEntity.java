package com.airshipvoyage.physics;

import com.airshipvoyage.AirshipVoyage;
import com.airshipvoyage.collision.ShipCollisions;
import com.airshipvoyage.collision.ShipRegistry;
import com.airshipvoyage.core.assembly.AssemblyPlan;
import com.airshipvoyage.core.assembly.AssemblyPlans;
import com.airshipvoyage.core.assembly.ConstructionState;
import com.airshipvoyage.core.collision.HullCollider;
import com.airshipvoyage.core.flight.FlightModel;
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
    private static final EntityDataAccessor<CompoundTag> DATA_CONSTRUCTION =
            SynchedEntityData.defineId(AirshipEntity.class, EntityDataSerializers.COMPOUND_TAG);
    /** Доли мощности моторов для вращения винтов: {"t": [..]} по порядку {@code layout().engines()}. */
    private static final EntityDataAccessor<CompoundTag> DATA_ENGINES =
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
        builder.define(DATA_ENGINES, new CompoundTag());
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
     * газ закачивается отдельно (этап IX; пока — командой {@code /airship gas}). Корабль лежит на опорах.
     */
    public void launch() {
        if (construction == null) return;
        for (GasCell cell : flight.cells()) {
            cell.setAmountMol(0);
            cell.setLeakPerSecond(0);
        }
        flight.massChanged();
        flight.place(new Vec3d(getX(), getY(), getZ()), flight.body().orientation());
        setConstruction(null);
        syncCells(true);
    }

    /** Коллизионные формы в локальных координатах корпуса: у стройки — только установленные детали. */
    public List<OrientedBox> hullShapes() {
        return construction != null ? constructionShapes : layout.hullShapes();
    }

    /** Детали для рендера: у стройки — только установленные. */
    public List<PartInstance> visibleParts() {
        return construction != null ? construction.installedParts() : layout.parts();
    }

    /** Камера {@code index} (по {@code layout().cells()}) подвешена — у стройки только установленные. */
    public boolean cellPresent(int index) {
        return construction == null || construction.isInstalled(layout.cells().get(index).id());
    }

    private void setLayout(ShipLayout newLayout) {
        FlightModel old = flight;
        this.layout = newLayout;
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

    public void setLayoutById(String id) {
        ShipLayouts.byId(id).ifPresent(this::setLayout);
    }

    public ShipLayout layout() { return layout; }

    /** Модель полёта (сервер). */
    public FlightModel flight() { return flight; }

    /** Окружение для физики — блоки и атмосфера этого мира. */
    public WorldEnvironment environment() { return new WorldEnvironment(level()); }

    /** Наполнение камер для рендера (клиент) — 0..1 по порядку {@code layout().cells()}. */
    public float[] cellFillForRender() { return cellFill; }

    // --- моторы: на сервере — последние отправленные доли мощности, на клиенте — полученные и углы винтов ---
    private float[] engineThrottle = new float[0];
    private float[] propAngle = new float[0], propAngleO = new float[0];

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
        if (!changed) return;
        ListTag list = new ListTag();
        for (int i = 0; i < engines.size(); i++) {
            engineThrottle[i] = (float) engines.get(i).throttle();
            list.add(net.minecraft.nbt.FloatTag.valueOf(engineThrottle[i]));
        }
        CompoundTag tag = new CompoundTag();
        tag.put("t", list);
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
                }
                applyConstructionTag();
                reapplyPosition();
            });
        } else if (DATA_CONSTRUCTION.equals(key)) {
            applyConstructionTag();
        } else if (DATA_ENGINES.equals(key)) {
            ListTag t = entityData.get(DATA_ENGINES).getList("t", Tag.TAG_FLOAT);
            engineThrottle = new float[t.size()];
            for (int i = 0; i < t.size(); i++) engineThrottle[i] = t.getFloat(i);
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
        // На случай, если позицию поменял кто-то извне (/tp, загрузка мира).
        Vec3d entityPos = new Vec3d(getX(), getY(), getZ());
        if (entityPos.distanceTo(body.position()) > 1e-6) body.setPosition(entityPos);
        Quat oldRot = body.orientation();
        if (construction != null) {
            // на стапеле корабль неподвижен: физика полёта не считается (ТЗ 5.4 — спуск только после постройки)
            body.freeze();
            setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            return;
        }

        try {
            flight.step(DT, environment());
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
        carryStandingEntities(entityPos, oldRot, p, body.orientation());
    }

    private void tickClient() {
        Vec3d oldPos = new Vec3d(getX(), getY(), getZ());
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
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        ShipRegistry.remove(this);
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
        }
        ListTag ballast = tag.getList("Ballast", Tag.TAG_COMPOUND);
        for (int i = 0; i < ballast.size(); i++) {
            CompoundTag b = ballast.getCompound(i);
            for (FlightModel.Ballast bag : flight.ballast()) {
                if (bag.id.equals(b.getString("Id"))) {
                    flight.dropBallast(bag.id, Math.max(0, bag.massKg() - b.getDouble("Kg")));
                }
            }
        }
        // моторы (ТЗ 8.4): команда телеграфа и фактические обороты
        ListTag engines = tag.getList("Engines", Tag.TAG_COMPOUND);
        for (int i = 0; i < engines.size(); i++) {
            CompoundTag c = engines.getCompound(i);
            var e = flight.engine(c.getString("Id"));
            if (e == null) continue;
            com.airshipvoyage.core.propulsion.Telegraph.byKey(c.getString("Telegraph")).ifPresent(e::setTelegraph);
            e.setThrottle(c.getDouble("Throttle"));
        }
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
        tag.putBoolean("Neutral", flight.neutral());
        ListTag cells = new ListTag();
        for (GasCell cell : flight.cells()) {
            CompoundTag c = new CompoundTag();
            c.putString("Id", cell.id());
            c.putString("Gas", cell.gas().name());
            c.putDouble("Mol", cell.amountMol());
            c.putDouble("Leak", cell.leakPerSecond());
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
        ListTag engines = new ListTag();
        for (var e : flight.engines()) {
            CompoundTag c = new CompoundTag();
            c.putString("Id", e.id());
            c.putString("Telegraph", e.telegraph().key());
            c.putDouble("Throttle", e.throttle());
            engines.add(c);
        }
        tag.put("Engines", engines);
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
