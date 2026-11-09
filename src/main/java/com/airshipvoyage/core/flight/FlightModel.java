package com.airshipvoyage.core.flight;

import com.airshipvoyage.core.math.Aabb;
import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.physics.Atmosphere;
import com.airshipvoyage.core.physics.GasCell;
import com.airshipvoyage.core.physics.LiftingGas;
import com.airshipvoyage.core.rigid.HullDrag;
import com.airshipvoyage.core.rigid.MassProperties;
import com.airshipvoyage.core.rigid.RigidBody;
import com.airshipvoyage.core.structure.CellSlot;
import com.airshipvoyage.core.structure.ShipLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.airshipvoyage.core.physics.PhysicalConstants.G;

/**
 * Полёт корабля как целого (ТЗ, 8.1–8.3, 16.8, 22.5): твёрдое тело + газовые камеры + балласт + контакт с землёй.
 * Без Minecraft: мир подставляется через {@link Environment}.
 *
 * <p>Силы за шаг:
 * <ul>
 *   <li>вес всей массы (каркас, оболочки, газ, балласт) — в центре масс;</li>
 *   <li>архимедова сила каждой камеры — в центре газа этой камеры (газ собирается у верха неполного мешка),
 *       поэтому асимметрия камер даёт дифферент и крен, а «центр плавучести выше центра масс» — остойчивость;</li>
 *   <li>сопротивление воздуха корпуса и демпфирование вращения;</li>
 *   <li>контакт с миром: в точках корпуса, вошедших в блоки, — пружина с демпфером по нормали и трение.</li>
 * </ul>
 *
 * <p>Контакт: суммарная жёсткость задана на единицу массы и делится между касающимися точками —
 * осадка на земле ≈ g / {@link #CONTACT_STIFFNESS} ≈ 2,5 см при любом числе точек, а явный интегратор
 * устойчив. Когда рядом есть твёрдые блоки, шаг тика делится на {@link #CONTACT_SUBSTEPS} подшагов.
 */
public final class FlightModel {
    /** Жёсткость контакта, Н/м на кг массы (собственная частота ≈ 3 Гц). */
    public static final double CONTACT_STIFFNESS = 400;
    /** Демпфирование контакта, Н·с/м на кг массы (≈ 0,7 от критического). */
    public static final double CONTACT_DAMPING = 28;
    /** Коэффициент трения каркаса о землю. */
    public static final double GROUND_FRICTION = 0.6;
    public static final int CONTACT_SUBSTEPS = 8;
    /**
     * Глубина, дальше которой сила контакта не растёт. Корабль, оказавшийся в земле (появился в склоне,
     * загрузился внутри постройки), выдавливается плавно, а не выстреливает, как сжатая пружина:
     * скорость выдавливания не больше CONTACT_STIFFNESS·MAX_CONTACT_DEPTH / CONTACT_DAMPING ≈ 3,6 м/с.
     */
    public static final double MAX_CONTACT_DEPTH = 0.25;
    /** Присоединённая масса как доля массы вытесненного камерами воздуха: вдоль оси, вертикально, борт. */
    public static final Vec3d ADDED_MASS_COEFFICIENTS = new Vec3d(0.1, 0.9, 0.9);

    /** Мир вокруг корабля. */
    public interface Environment {
        /** Высота над уровнем моря для мировой Y, м (масштаб атмосферы, ТЗ 8.8). */
        double altitudeMeters(double worldY);

        Atmosphere atmosphere();

        /** Ветер в точке, м/с. */
        default Vec3d wind(Vec3d worldPoint) { return Vec3d.ZERO; }

        ContactQuery contacts();
    }

    /** Твёрдый мир (блоки) для контакта. */
    public interface ContactQuery {
        ContactQuery NONE = new ContactQuery() {
            @Override public boolean mayContact(Aabb bounds) { return false; }
            @Override public Penetration probe(Vec3d p) { return null; }
        };

        /** Быстрая проверка: есть ли вообще твёрдое в этой области. */
        boolean mayContact(Aabb worldBounds);

        /** Насколько точка вошла в твёрдое и куда её выталкивать; {@code null}, если точка свободна. */
        Penetration probe(Vec3d worldPoint);
    }

    /** @param normal единичная нормаль наружу из твёрдого @param depth глубина, м */
    public record Penetration(Vec3d normal, double depth) {}

    /** Сброшенный балласт / груз в точке корпуса. */
    public static final class Ballast {
        public final String id;
        public final Vec3d local;
        private double massKg;

        public Ballast(String id, Vec3d local, double massKg) {
            this.id = id;
            this.local = local;
            this.massKg = Math.max(0, massKg);
        }

        public double massKg() { return massKg; }
    }

    /** Итог шага — для приборов, отладки и (позже) системы повреждений. */
    public record StepReport(int contactPoints, double contactForceN, double maxImpactSpeed) {
        public boolean grounded() { return contactPoints > 0; }
    }

    private final ShipLayout layout;
    private final List<GasCell> cells = new ArrayList<>();
    private final List<Ballast> ballast = new ArrayList<>();
    private final RigidBody body;
    private final HullDrag drag;
    /** Отладка: вес и архимедова сила не действуют (корабль «идеально уравновешен»). */
    private boolean neutral;
    private double massAtLastBuild;
    private StepReport lastReport = new StepReport(0, 0, 0);

    public FlightModel(ShipLayout layout, LiftingGas gas) {
        this.layout = layout;
        for (CellSlot s : layout.cells()) {
            Vec3d c = s.bay().center();
            cells.add(new GasCell(s.id(), s.capacityM3(), s.envelopeMassKg(), c.x(), c.y(), c.z(), gas));
        }
        this.body = new RigidBody(buildMass());
        this.massAtLastBuild = body.massKg();

        double displaced = 0;
        for (CellSlot s : layout.cells()) displaced += s.capacityM3();
        Vec3d k = ADDED_MASS_COEFFICIENTS;
        double airMass = 1.225 * displaced;
        body.setAddedMassLocal(new Vec3d(k.x() * airMass, k.y() * airMass, k.z() * airMass));

        Vec3d size = layout.localBoundsMax().sub(layout.localBoundsMin());
        // голый каркас почти весь продувается; мешки внутри перекрывают его
        drag = HullDrag.estimateFromSize(size, 1.2, layout.cells().isEmpty() ? 0.85 : 0.2);
    }

    // ------------------------------------------------------------------ масса

    private MassProperties buildMass() {
        MassProperties.Builder mb = layout.addStructureMass(MassProperties.builder());
        for (int i = 0; i < cells.size(); i++) {
            mb.addBox(layout.cells().get(i).bay(), cells.get(i).totalMassKg());
        }
        for (Ballast b : ballast) mb.addPoint(b.local, b.massKg);
        return mb.build();
    }

    private double currentMassKg() {
        double m = layout.structureMassKg();
        for (GasCell c : cells) m += c.totalMassKg();
        for (Ballast b : ballast) m += b.massKg;
        return m;
    }

    /** Пересобрать массу, если она заметно изменилась (утечка, сброс балласта, наполнение). */
    private void refreshMass(boolean force) {
        double m = currentMassKg();
        if (force || Math.abs(m - massAtLastBuild) > 0.05) {
            body.setMassProperties(buildMass());
            massAtLastBuild = m;
        }
    }

    // ------------------------------------------------------------------ шаг

    public StepReport step(double dt, Environment env) {
        // газ: утечки, клапаны, перегрев — раз за тик
        for (int i = 0; i < cells.size(); i++) {
            cells.get(i).tick(dt, airAtLocal(layout.cells().get(i).bay().center(), env));
        }
        refreshMass(false);

        Vec3d[] box = layout.worldAabb(body.position(), body.orientation());
        Aabb bounds = new Aabb(box[0], box[1]).inflate(0.5, 0.5, 0.5);
        boolean contactPossible = env.contacts().mayContact(bounds);
        int n = contactPossible ? CONTACT_SUBSTEPS : 1;
        double h = dt / n;

        int contacts = 0;
        double force = 0, impact = 0;
        for (int s = 0; s < n; s++) {
            applyAeroAndGravity(env);
            if (contactPossible) {
                double[] r = applyContacts(env.contacts());
                contacts = (int) r[0];
                force = r[1];
                impact = Math.max(impact, r[2]);
            }
            body.step(h);
        }
        lastReport = new StepReport(contacts, force, impact);
        return lastReport;
    }

    private void applyAeroAndGravity(Environment env) {
        if (!neutral) {
            body.applyCentralForce(new Vec3d(0, -body.massKg() * G, 0));
            for (int i = 0; i < cells.size(); i++) {
                CellSlot slot = layout.cells().get(i);
                GasCell cell = cells.get(i);
                Atmosphere.State air = airAtLocal(slot.bay().center(), env);
                double b = cell.buoyancyN(air);
                if (b <= 0) continue;
                body.applyForceAtLocal(new Vec3d(0, b, 0), gasCentreLocal(slot, cell.fillFraction(air)));
            }
        }
        Vec3d pos = body.position();
        double rho = env.atmosphere().at(env.altitudeMeters(pos.y())).densityKgM3();
        drag.apply(body, env.wind(pos), rho);
    }

    /** Центр газа: в неполном мешке газ занимает верхнюю часть отсека. */
    static Vec3d gasCentreLocal(CellSlot slot, double fill) {
        OrientedBox bay = slot.bay();
        double halfY = bay.halfExtents().y();
        return bay.center().add(0, halfY * (1 - fill), 0);
    }

    /** @return {число точек, сумма нормальных сил, макс. скорость сближения} */
    private double[] applyContacts(ContactQuery query) {
        List<Vec3d> samples = layout.contactSamples();
        List<Vec3d> points = new ArrayList<>();
        List<Penetration> pens = new ArrayList<>();
        for (Vec3d local : samples) {
            Vec3d wp = body.localToWorld(local);
            Penetration p = query.probe(wp);
            if (p != null && p.depth() > 0) {
                points.add(wp);
                pens.add(p);
            }
        }
        int n = points.size();
        if (n == 0) return new double[]{0, 0, 0};

        double mEff = body.effectiveVerticalMassKg();
        double k = CONTACT_STIFFNESS * mEff / n;
        double c = CONTACT_DAMPING * mEff / n;
        double total = 0, impact = 0;
        for (int i = 0; i < n; i++) {
            Vec3d wp = points.get(i);
            Penetration p = pens.get(i);
            Vec3d v = body.velocityAt(wp);
            double vn = v.dot(p.normal());
            impact = Math.max(impact, -vn);
            double fn = k * Math.min(p.depth(), MAX_CONTACT_DEPTH) - c * vn;
            if (fn <= 0) continue; // контакт только отталкивает
            Vec3d f = p.normal().scale(fn);
            Vec3d vt = v.sub(p.normal().scale(vn));
            double vtLen = vt.length();
            if (vtLen > 1e-6) {
                // кулоново трение, сглаженное вязким около нуля (иначе дрожь при покое)
                double ft = Math.min(GROUND_FRICTION * fn, c * vtLen);
                f = f.add(vt.scale(-ft / vtLen));
            }
            body.applyForceAt(f, wp);
            total += fn;
        }
        return new double[]{n, total, impact};
    }

    private Atmosphere.State airAtLocal(Vec3d local, Environment env) {
        double y = body.localToWorld(local).y();
        return env.atmosphere().at(env.altitudeMeters(y));
    }

    // ------------------------------------------------------------------ управление газом и балластом

    /** Наполнить камеру до доли {@code fraction} её объёма при текущих условиях. */
    public void setFill(int cellIndex, double fraction, Environment env) {
        GasCell cell = cells.get(cellIndex);
        Atmosphere.State air = airAtLocal(layout.cells().get(cellIndex).bay().center(), env);
        cell.setAmountMol(cell.molesForFill(fraction, air));
        refreshMass(true);
    }

    public void setFillAll(double fraction, Environment env) {
        for (int i = 0; i < cells.size(); i++) setFill(i, fraction, env);
    }

    /**
     * «Взвешивание» (weigh-off): наполнить все камеры одинаково так, чтобы корабль был в равновесии
     * на текущей высоте. Ниже высоты давления равновесие безразличное — корабль висит на любой высоте.
     *
     * @return требуемое наполнение; &gt; 1 — газа не хватит, камеры наполнены полностью, корабль тяжёл
     */
    public double weighOff(Environment env) {
        double dry = layout.structureMassKg();
        for (GasCell c : cells) dry += c.massEmptyKg();
        for (Ballast b : ballast) dry += b.massKg;
        double liftPerFill = 0; // кг полезной подъёмной силы на единицу наполнения
        for (int i = 0; i < cells.size(); i++) {
            GasCell cell = cells.get(i);
            Atmosphere.State air = airAtLocal(layout.cells().get(i).bay().center(), env);
            double gasDensity = cell.gas().density(air.pressurePa(), cell.gasTemperature(air));
            liftPerFill += (air.densityKgM3() - gasDensity) * cell.capacityM3();
        }
        if (liftPerFill <= 0) return Double.POSITIVE_INFINITY;
        double f = dry / liftPerFill;
        setFillAll(Math.min(1.0, f), env);
        return f;
    }

    public void addBallast(Ballast b) {
        ballast.add(b);
        refreshMass(true);
    }

    /** Сбросить до {@code kg} балласта из мешка {@code id} (или из всех, если id = "all"). @return сброшено, кг */
    public double dropBallast(String id, double kg) {
        double dropped = 0;
        for (Ballast b : ballast) {
            if (!"all".equals(id) && !b.id.equals(id)) continue;
            double d = Math.min(b.massKg, kg - dropped);
            b.massKg -= d;
            dropped += d;
            if (dropped >= kg) break;
        }
        refreshMass(true);
        return dropped;
    }

    // ------------------------------------------------------------------ баланс (приборы, ТЗ 24.4)

    /** @param heavinessKg &gt; 0 — корабль тяжелее воздуха на столько кг */
    public record Balance(double massKg, double buoyancyKg, double heavinessKg, Vec3d centreOfMassLocal) {}

    public Balance balance(Environment env) {
        double buoyancy = 0;
        for (int i = 0; i < cells.size(); i++) {
            buoyancy += cells.get(i).buoyancyN(airAtLocal(layout.cells().get(i).bay().center(), env)) / G;
        }
        double m = currentMassKg();
        return new Balance(m, buoyancy, m - buoyancy, body.massProperties().centerOfMass());
    }

    /** Доля наполнения камеры при текущих условиях. */
    public double fill(int cellIndex, Environment env) {
        return cells.get(cellIndex).fillFraction(airAtLocal(layout.cells().get(cellIndex).bay().center(), env));
    }

    // ------------------------------------------------------------------ доступ

    public ShipLayout layout() { return layout; }
    public RigidBody body() { return body; }
    public List<GasCell> cells() { return Collections.unmodifiableList(cells); }
    public List<Ballast> ballast() { return Collections.unmodifiableList(ballast); }
    public boolean neutral() { return neutral; }
    public void setNeutral(boolean neutral) { this.neutral = neutral; }
    public StepReport lastReport() { return lastReport; }

    public int cellIndex(String id) {
        for (int i = 0; i < cells.size(); i++) if (cells.get(i).id().equals(id)) return i;
        return -1;
    }

    /** После загрузки состояния газа/балласта извне. */
    public void massChanged() { refreshMass(true); }

    /** Установить позу без изменения скоростей. */
    public void place(Vec3d position, Quat orientation) {
        body.setOrientation(orientation);
        body.setPosition(position);
    }
}
