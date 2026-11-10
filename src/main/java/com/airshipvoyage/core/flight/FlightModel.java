package com.airshipvoyage.core.flight;

import com.airshipvoyage.core.math.Aabb;
import com.airshipvoyage.core.math.OrientedBox;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.physics.Atmosphere;
import com.airshipvoyage.core.physics.GasCell;
import com.airshipvoyage.core.physics.LiftingGas;
import com.airshipvoyage.core.propulsion.Engine;
import com.airshipvoyage.core.propulsion.Telegraph;
import com.airshipvoyage.core.rigid.HullDrag;
import com.airshipvoyage.core.rigid.MassProperties;
import com.airshipvoyage.core.rigid.RigidBody;
import com.airshipvoyage.core.structure.CellSlot;
import com.airshipvoyage.core.structure.ControlSurface;
import com.airshipvoyage.core.structure.EngineMount;
import com.airshipvoyage.core.structure.ShipLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * <p>Контакт: суммарная жёсткость задана на единицу эффективной вертикальной массы (собственная + присоединённая)
 * и делится между касающимися точками — осадка не зависит от числа точек, а явный интегратор устойчив.
 * Осадка = вес / ({@link #CONTACT_STIFFNESS}·(m + m_прис)): у каркаса без камер ≈ g/400 ≈ 2,5 см, у прототипа
 * с пустыми камерами ≈ 1,1 см (1400 кг, присоединённая масса ≈ 1740 кг — сверено игровым тестом).
 * Когда рядом есть твёрдые блоки, шаг тика делится на {@link #CONTACT_SUBSTEPS} подшагов.
 */
public final class FlightModel {
    /** Жёсткость контакта, Н/м на кг массы (собственная частота ≈ 3 Гц). */
    public static final double CONTACT_STIFFNESS = 400;
    /** Демпфирование контакта, Н·с/м на кг массы (≈ 0,7 от критического). */
    public static final double CONTACT_DAMPING = 28;
    /** Коэффициент трения каркаса о землю. */
    public static final double GROUND_FRICTION = 0.6;
    public static final int CONTACT_SUBSTEPS = 8;
    /** Наибольшее смещение корпуса за подшаг при возможном контакте, м (меньше глубины контакта 0,25). */
    public static final double MAX_SUBSTEP_TRAVEL = 0.2;
    /** Предел подшагов за тик (≈ 64 · 0,2 = 12,8 м за тик — 256 м/с, с запасом выше любых скоростей корабля). */
    public static final int MAX_SUBSTEPS = 64;
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

        /**
         * Снимок мира в области на один тик (игровой слой собирает коробки блоков один раз — {@link CollisionBoxes}).
         * По умолчанию — сам запрос (тестовые «полупространства»).
         */
        default ContactQuery snapshot(Aabb region) { return this; }

        /** Коробки твёрдого, пересекающие область, — для контакта граней деталей; пусто — запрос коробок не знает. */
        default List<Aabb> boxesIn(Aabb region) { return List.of(); }
    }

    /**
     * @param normal единичная нормаль наружу из твёрдого
     * @param depth  глубина, м
     * @param box    коробка блока, в которую вошла точка ({@code null}, если запрос коробок не знает)
     */
    public record Penetration(Vec3d normal, double depth, Aabb box) {
        public Penetration(Vec3d normal, double depth) { this(normal, depth, null); }
    }

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
    private final List<Engine> engines = new ArrayList<>();
    /** Люди и грузы на борту (ТЗ 8.3): масса в точке корпуса. Меняется каждые несколько тиков из мира. */
    private List<Payload> payload = List.of();
    /**
     * Грань входа для каждой точки контакта, пока точка в блоке (аудит 3, AUD-03-01): глубоко вошедшая точка
     * выталкивается обратно через ту грань, через которую вошла, а не через ближайшую — иначе пластина, ушедшая
     * за середину блока, выдавливалась бы насквозь с другой стороны.
     */
    private Vec3d[] entryNormals = new Vec3d[0];
    /** То же для контактов граней: пара «форма детали — коробка блока» → грань входа. */
    private java.util.Map<ShapeBox, Vec3d> faceEntries = new java.util.HashMap<>();
    /** Рули (ТЗ 8.5): команда и фактическое отклонение, градусы. Положительные — влево и нос вверх. */
    private double rudderCmdDeg, elevatorCmdDeg, rudderDeg, elevatorDeg;
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
        for (EngineMount m : layout.engines()) engines.add(new Engine(m));
        this.body = new RigidBody(buildMass());
        this.massAtLastBuild = body.massKg();

        double displaced = 0;
        for (CellSlot s : layout.cells()) displaced += s.capacityM3();
        Vec3d k = ADDED_MASS_COEFFICIENTS;
        double airMass = 1.225 * displaced;
        body.setAddedMassLocal(new Vec3d(k.x() * airMass, k.y() * airMass, k.z() * airMass));

        // габарит без оперения: стабилизаторы — несущие плоскости, их сила считается отдельно (applySurfaces)
        Vec3d size = layout.aeroSize();
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
        for (Payload p : payload) mb.addPoint(p.local(), p.massKg());
        return mb.build();
    }

    private double currentMassKg() {
        double m = layout.structureMassKg();
        for (GasCell c : cells) m += c.totalMassKg();
        for (Ballast b : ballast) m += b.massKg;
        for (Payload p : payload) m += p.massKg();
        return m;
    }

    // ------------------------------------------------------------------ люди и грузы на борту (ТЗ 8.3)

    /** Масса на борту: точка в локальных координатах корпуса и масса, кг. */
    public record Payload(Vec3d local, double massKg) {}

    /** Порог пересборки массы при перемещении людей по палубе, кг·м: мелкие шаги не пересобирают тензор каждый тик. */
    public static final double PAYLOAD_MOMENT_THRESHOLD = 20;

    /**
     * Обновить людей и грузы на борту (AV-014). Масса пересобирается, если изменилась суммарная масса больше чем на
     * 0,5 кг или момент — больше чем на {@value #PAYLOAD_MOMENT_THRESHOLD} кг·м (≈ человек сдвинулся на 25 см).
     */
    public void setPayload(List<Payload> next) {
        double m0 = 0, m1 = 0;
        Vec3d s0 = Vec3d.ZERO, s1 = Vec3d.ZERO;
        for (Payload p : payload) { m0 += p.massKg(); s0 = s0.add(p.local().scale(p.massKg())); }
        for (Payload p : next) { m1 += p.massKg(); s1 = s1.add(p.local().scale(p.massKg())); }
        if (Math.abs(m1 - m0) < 0.5 && s1.distanceTo(s0) < PAYLOAD_MOMENT_THRESHOLD) return;
        payload = List.copyOf(next);
        refreshMass(true);
    }

    public List<Payload> payload() { return payload; }

    /** Клапаны газа всех камер открыты/закрыты (стравливание с поста, ТЗ 7.4, 9.1). */
    public void setVenting(boolean open) {
        for (GasCell c : cells) c.setManualValveOpen(open);
    }

    public boolean venting() {
        return cells.stream().anyMatch(GasCell::manualValveOpen);
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
        tickGas(dt, env);
        for (Engine e : engines) e.tick(dt);
        rudderDeg = servo(rudderDeg, rudderCmdDeg, dt);
        elevatorDeg = servo(elevatorDeg, elevatorCmdDeg, dt);

        return stepBody(dt, env);
    }

    /** Газ живёт и на стапеле: наполнение, клапаны, утечки (ТЗ 5.4, этап IX; 7.3). */
    public void tickGas(double dt, Environment env) {
        // газ: утечки, клапаны, перегрев — раз за тик
        for (int i = 0; i < cells.size(); i++) {
            cells.get(i).tick(dt, airAtLocal(layout.cells().get(i).bay().center(), env));
        }
        refreshMass(false);
    }

    private StepReport stepBody(double dt, Environment env) {
        Vec3d[] box = layout.worldAabb(body.position(), body.orientation());
        Aabb bounds = new Aabb(box[0], box[1]).inflate(0.5, 0.5, 0.5);
        // снимок блоков на весь тик: габарит плюс путь, который корпус может пройти за тик (с вращением)
        double reach = (body.velocity().length() + body.angularVelocity().length() * layout.boundingRadius()) * dt
                + MAX_CONTACT_DEPTH + 0.5;
        Aabb swept = bounds.inflate(reach, reach, reach);
        ContactQuery world = env.contacts().snapshot(swept);
        // Аудит 3, AUD-03-01: возможность контакта — по всей области пути за тик, а не по нынешнему габариту:
        // на 38 м/с корпус проходит 1,9 м и проскакивал блок, ни разу не проверив контакт. Подшагов столько,
        // чтобы за подшаг корпус смещался не больше чем на MAX_SUBSTEP_TRAVEL — точки контакта не перепрыгивают блок.
        boolean contactPossible = world.mayContact(swept);
        double travel = (body.velocity().length() + body.angularVelocity().length() * layout.boundingRadius()) * dt;
        int n = contactPossible
                ? Math.min(MAX_SUBSTEPS, Math.max(CONTACT_SUBSTEPS, (int) Math.ceil(travel / MAX_SUBSTEP_TRAVEL)))
                : 1;
        double h = dt / n;

        int contacts = 0;
        double force = 0, impact = 0;
        for (int s = 0; s < n; s++) {
            applyAeroAndGravity(env);
            if (contactPossible) {
                double[] r = applyContacts(world);
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
        drag.apply(body, env.wind(pos), rho, dragCenterLocal());
        applyThrust(env, rho);
        applySurfaces(env, rho);
    }

    /**
     * Точка приложения сопротивления корпуса: середина габарита по длине и ширине (ось корпуса), по высоте — на
     * уровне ЦМ. Высота оставлена у ЦМ: дифферент от сопротивления выше ЦМ уже учтён выбором высоты тяги (D-114),
     * а по горизонтали сила должна оставаться на оси корпуса, когда груз сдвигает ЦМ (D-133).
     */
    private Vec3d dragCenterLocal() {
        Vec3d lo = layout.localBoundsMin(), hi = layout.localBoundsMax();
        return new Vec3d((lo.x() + hi.x()) / 2, body.massProperties().centerOfMass().y(), (lo.z() + hi.z()) / 2);
    }

    // ------------------------------------------------------------------ оперение и рули (ТЗ 8.5)

    /** Наклон кривой подъёмной силы стабилизатора малого удлинения (λ ≈ 1,5–2), 1/рад. */
    public static final double FIN_LIFT_SLOPE = 2.3;
    /**
     * Эффективность руля τ: отклонение на δ меняет угол обтекания всей плоскости на τ·δ. Руль — половина хорды
     * стабилизатора; теория тонкого профиля для закрылка в 50 % хорды даёт τ ≈ 0,69 (было 0,5 — грубая «доля
     * площади»; поворот был в 2,3 раза медленнее исторического, жалоба владельца, D-132).
     */
    public static final double RUDDER_EFFECTIVENESS = 0.7;
    /** Предельное отклонение рулей, градусы (у цеппелинов — около 30°). */
    public static final double MAX_DEFLECTION_DEG = 30;
    /** Скорость перекладки рулей (штурвальный привод), градусов в секунду. */
    public static final double SERVO_DEG_PER_S = 15;
    /** Срыв потока: дальше этого угла сила не растёт, рад (≈ 20°). */
    public static final double STALL_RAD = 0.35;

    private static double servo(double actual, double command, double dt) {
        double step = SERVO_DEG_PER_S * dt, d = command - actual;
        return Math.abs(d) <= step ? command : actual + Math.signum(d) * step;
    }

    /**
     * Сила на каждом стабилизаторе: F = ½·ρ·V²·S·a·(τ·δ − α) вдоль его нормали, где V и α — скорость и угол
     * набегающего потока в центре давления (с учётом вращения корпуса), δ — отклонение руля. Без воздушной скорости
     * руль ничего не делает (ТЗ 8.5); на заднем ходу руль не работает (поток с задней кромки), стабилизатор — работает.
     */
    private void applySurfaces(Environment env, double rho) {
        for (ControlSurface s : layout.controlSurfaces()) {
            Vec3d at = body.localToWorld(s.center());
            Vec3d local = body.orientation().inverseRotate(body.velocityAt(at).sub(env.wind(at)));
            double u = local.x(), w = local.dot(s.normal());
            double v2 = u * u + w * w;
            if (v2 < 1e-8) continue;
            double alpha = Math.atan2(w, Math.abs(u));
            double deflection = Math.toRadians(s.kind() == ControlSurface.Kind.RUDDER ? rudderDeg : elevatorDeg);
            double angle = (u > 0 ? RUDDER_EFFECTIVENESS * deflection : 0) - alpha;
            angle = Math.max(-STALL_RAD, Math.min(STALL_RAD, angle));
            double force = 0.5 * rho * v2 * s.areaM2() * FIN_LIFT_SLOPE * angle;
            body.applyForceAt(body.orientation().rotate(s.normal()).scale(force), at);
        }
    }

    /** Команда рулю направления, градусы (&gt; 0 — влево), ограничена ±{@value #MAX_DEFLECTION_DEG}. */
    public void setRudder(double deg) { rudderCmdDeg = clampDeflection(deg); }
    /** Команда рулю высоты, градусы (&gt; 0 — нос вверх). */
    public void setElevator(double deg) { elevatorCmdDeg = clampDeflection(deg); }
    public double rudderCommandDeg() { return rudderCmdDeg; }
    public double elevatorCommandDeg() { return elevatorCmdDeg; }
    public double rudderDeg() { return rudderDeg; }
    public double elevatorDeg() { return elevatorDeg; }

    /** Восстановление из сохранения: команды и фактические отклонения. */
    public void restoreRudders(double rudderCmd, double rudder, double elevatorCmd, double elevator) {
        rudderCmdDeg = clampDeflection(rudderCmd);
        rudderDeg = clampDeflection(rudder);
        elevatorCmdDeg = clampDeflection(elevatorCmd);
        elevatorDeg = clampDeflection(elevator);
    }

    private static double clampDeflection(double deg) {
        return Math.max(-MAX_DEFLECTION_DEG, Math.min(MAX_DEFLECTION_DEG, deg));
    }

    /**
     * Тяга моторов (ТЗ 8.4): вдоль оси винта в точке крепления, по скорости набегающего потока в этой точке.
     * Не зависит от режима «нейтрально». Тяга винта и мощность мотора без наддува падают с плотностью воздуха —
     * обе в отношении ρ/ρ₀.
     */
    private void applyThrust(Environment env, double rho) {
        double sigma = rho / 1.225;
        for (Engine e : engines) {
            if (e.throttle() == 0) continue;
            Vec3d at = body.localToWorld(e.mount().thrustPoint());
            Vec3d dir = body.orientation().rotate(e.mount().axis());
            double axial = body.velocityAt(at).sub(env.wind(at)).dot(dir);
            body.applyForceAt(dir.scale(e.thrustN(axial) * sigma), at);
        }
    }

    // ------------------------------------------------------------------ моторы (ТЗ 8.4, 9.2)

    public List<Engine> engines() { return Collections.unmodifiableList(engines); }

    /** Мотор по ID детали-гондолы; null — нет такого. */
    public Engine engine(String id) {
        for (Engine e : engines) if (e.id().equals(id)) return e;
        return null;
    }

    /**
     * Команда телеграфа одному мотору или всем ({@code "all"}) — как с мостика (ТЗ 9.2).
     * @return сколько моторов получили команду
     */
    public int setTelegraph(String id, Telegraph t) {
        int n = 0;
        for (Engine e : engines) {
            if ("all".equals(id) || e.id().equals(id)) {
                e.setTelegraph(t);
                n++;
            }
        }
        return n;
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
        // пары «форма детали — коробка блока», уже охваченные точками на рёбрах
        Set<ShapeBox> touched = new HashSet<>();
        if (entryNormals.length != samples.size()) entryNormals = new Vec3d[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            Vec3d wp = body.localToWorld(samples.get(i));
            Penetration p = query.probe(wp);
            if (p == null || p.box() == null || p.depth() <= 0) {
                entryNormals[i] = null; // вне блока или на самой грани — грань входа ещё не определена
            } else if (entryNormals[i] == null) {
                entryNormals[i] = p.normal();
            } else {
                p = alongNormal(entryNormals[i], wp, p.box());
            }
            if (p != null && p.depth() > 0) {
                points.add(wp);
                pens.add(p);
                if (p.box() != null) for (int k : layout.sampleShapes(i)) touched.add(new ShapeBox(k, p.box()));
            }
        }
        addFaceContacts(query, touched, points, pens);
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

    private record ShapeBox(int shape, Aabb box) {}

    /** Глубина точки в коробке вдоль заданной осевой нормали (наружу из коробки). */
    static Penetration alongNormal(Vec3d n, Vec3d p, Aabb box) {
        int axis = Math.abs(n.x()) > 0.5 ? 0 : Math.abs(n.y()) > 0.5 ? 1 : 2;
        double sign = n.get(axis);
        double depth = sign > 0 ? box.max(axis) - p.get(axis) : p.get(axis) - box.min(axis);
        return new Penetration(n, depth, box);
    }

    /**
     * Ревью 2, №5: точки контакта стоят только на рёбрах деталей, и блок, вошедший в середину грани (выступ под
     * серединой киля), их не задевал — киль проходил сквозь него. Для каждой пары «форма детали — коробка блока»,
     * которые пересекаются, но ни одна точка этой формы в коробку не попала, добавляется контакт грани.
     */
    private void addFaceContacts(ContactQuery query, Set<ShapeBox> touched, List<Vec3d> points, List<Penetration> pens) {
        List<OrientedBox> shapes = layout.hullShapes();
        java.util.Map<ShapeBox, Vec3d> entries = new java.util.HashMap<>();
        for (int k = 0; k < shapes.size(); k++) {
            OrientedBox w = shapes.get(k).transformed(body.position(), body.orientation());
            Aabb wb = new Aabb(w.aabbMin(), w.aabbMax());
            for (Aabb a : query.boxesIn(wb)) {
                ShapeBox key = new ShapeBox(k, a);
                // касание гранью (нулевой объём пересечения) — не контакт
                if (touched.contains(key)
                        || !w.intersectsAabb(a.min().add(1e-6, 1e-6, 1e-6), a.max().sub(new Vec3d(1e-6, 1e-6, 1e-6)))) continue;
                FaceContact c = faceContact(w, wb, a, query, faceEntries.get(key));
                if (c == null || c.depth() <= 0) continue;
                entries.put(key, c.normal());
                points.add(c.point());
                pens.add(new Penetration(c.normal(), c.depth(), a));
            }
        }
        faceEntries = entries;
    }

    /** Контакт грани: точка на поверхности детали, нормаль выталкивания (наружу из блока), глубина. */
    record FaceContact(Vec3d point, Vec3d normal, double depth) {}

    /**
     * Контакт формы детали {@code w} (её AABB — {@code wb}) с коробкой блока {@code a}. Как и для точки: выталкиваем
     * к ближайшей открытой грани блока. Глубина вдоль нормали ищется лучами через область перекрытия (4 угла и центр):
     * луч идёт по нормали из-за детали и находит её поверхность, обращённую к грани; берётся самый глубокий.
     * Все грани закрыты (деталь глубоко в массиве) — вверх, не больше 0,5 м, как у точек.
     */
    static FaceContact faceContact(OrientedBox w, Aabb wb, Aabb a, ContactQuery query) {
        return faceContact(w, wb, a, query, null);
    }

    /** @param entryFace грань входа, запомненная на прошлых подшагах: тогда выталкиваем только через неё */
    static FaceContact faceContact(OrientedBox w, Aabb wb, Aabb a, ContactQuery query, Vec3d entryFace) {
        FaceContact best = null, up = null;
        for (int axis = 0; axis < 3; axis++) {
            int t1 = (axis + 1) % 3, t2 = (axis + 2) % 3;
            double lo1 = Math.max(a.min(t1), wb.min(t1)), hi1 = Math.min(a.max(t1), wb.max(t1));
            double lo2 = Math.max(a.min(t2), wb.min(t2)), hi2 = Math.min(a.max(t2), wb.max(t2));
            if (lo1 > hi1 || lo2 > hi2) continue;
            double[][] uv = {{lo1, lo2}, {hi1, lo2}, {lo1, hi2}, {hi1, hi2}, {(lo1 + hi1) / 2, (lo2 + hi2) / 2}};
            for (int sign = -1; sign <= 1; sign += 2) {
                Vec3d push = CollisionBoxes.unit(axis, sign);
                if (entryFace != null && push.dot(entryFace) < 0.5) continue;
                double start = sign > 0 ? wb.min(axis) - 1 : wb.max(axis) + 1;
                double[] depths = new double[uv.length];
                Vec3d[] entries = new Vec3d[uv.length];
                double deepest = 0;
                for (int i = 0; i < uv.length; i++) {
                    double t = w.rayHit(point(axis, start, t1, uv[i][0], t2, uv[i][1]), push);
                    if (Double.isNaN(t)) continue;
                    double entry = start + sign * t;
                    depths[i] = sign > 0 ? a.max(axis) - entry : entry - a.min(axis);
                    entries[i] = point(axis, entry, t1, uv[i][0], t2, uv[i][1]);
                    deepest = Math.max(deepest, depths[i]);
                }
                if (deepest <= 0) continue;
                // точка опоры — среднее самых глубоких: у плоского касания это центр перекрытия, а не угол блока
                Vec3d sum = Vec3d.ZERO;
                int count = 0;
                for (int i = 0; i < uv.length; i++) {
                    if (entries[i] != null && depths[i] >= deepest - 1e-6) {
                        sum = sum.add(entries[i]);
                        count++;
                    }
                }
                Vec3d at = sum.scale(1.0 / count);
                if (axis == 1 && sign > 0) up = new FaceContact(at, push, Math.min(deepest, 0.5));
                if (entryFace != null) return new FaceContact(at, push, deepest);
                double face = sign > 0 ? a.max(axis) : a.min(axis);
                if (query.probe(point(axis, face + sign * 1e-4, t1, uv[4][0], t2, uv[4][1])) != null) continue; // закрыта
                if (best == null || deepest < best.depth()) best = new FaceContact(at, push, deepest);
            }
        }
        return best != null ? best : up;
    }

    private static Vec3d point(int axis, double v, int t1, double v1, int t2, double v2) {
        double[] c = new double[3];
        c[axis] = v;
        c[t1] = v1;
        c[t2] = v2;
        return new Vec3d(c[0], c[1], c[2]);
    }

    private Atmosphere.State airAtLocal(Vec3d local, Environment env) {
        double y = body.localToWorld(local).y();
        return env.atmosphere().at(env.altitudeMeters(y));
    }

    // ------------------------------------------------------------------ управление газом и балластом

    /** Заправка с конечным запасом: воздух берётся на высоте именно этой камеры (ТЗ 7.3–7.4). */
    public com.airshipvoyage.core.physics.GasFilling.Result supplyGas(int cellIndex, LiftingGas gas,
            double availableMol, double dt, Environment env) {
        var result = com.airshipvoyage.core.physics.GasFilling.transfer(cells.get(cellIndex), gas, availableMol, dt,
                airAtLocal(layout.cells().get(cellIndex).bay().center(), env));
        if (result.transferredMol() > 0) refreshMass(true);
        return result;
    }

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
        for (Payload p : payload) dry += p.massKg(); // люди и груз на борту (найдено тестом AV-014)
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

    /** Больше этого в один мешок балласта при загрузке не принимается (вся вода прототипа — 200 кг). */
    public static final double MAX_BALLAST_PER_BAG_KG = 200;

    /**
     * Восстановить массу балласта из сохранения — абсолютное значение, а не «сброс» от начального запаса
     * (аудит 3, AUD-03-02: при смене начального распределения часть сохранённой воды пропадала).
     * Значение проверяется: не число и отрицательное — 0, больше {@value #MAX_BALLAST_PER_BAG_KG} — предел.
     */
    public void restoreBallast(String id, double kg) {
        double v = Double.isFinite(kg) ? Math.max(0, Math.min(MAX_BALLAST_PER_BAG_KG, kg)) : 0;
        for (Ballast b : ballast) if (b.id.equals(id)) b.massKg = v;
        refreshMass(true);
    }

    /**
     * Перекачать до {@code kg} водяного балласта из мешка {@code from} в мешок {@code to} (ТЗ 8.3, AV-027):
     * общая масса не меняется, сдвигается центр масс — так дифферентуют корабль. Ограничено водой в исходном
     * мешке и местом в приёмном ({@value #MAX_BALLAST_PER_BAG_KG} кг).
     * @return перекачано, кг
     */
    public double transferBallast(String from, String to, double kg) {
        Ballast src = null, dst = null;
        for (Ballast b : ballast) {
            if (b.id.equals(from)) src = b;
            if (b.id.equals(to)) dst = b;
        }
        if (src == null || dst == null || src == dst || !(kg > 0)) return 0;
        double moved = Math.min(kg, Math.min(src.massKg, MAX_BALLAST_PER_BAG_KG - dst.massKg));
        if (moved <= 0) return 0;
        src.massKg -= moved;
        dst.massKg += moved;
        refreshMass(true);
        return moved;
    }

    /** Мешок балласта ближе всего к носу (наибольший x) или к корме; null — балласта нет. */
    public Ballast endBallast(boolean bow) {
        Ballast best = null;
        for (Ballast b : ballast) {
            if (best == null || (bow ? b.local.x() > best.local.x() : b.local.x() < best.local.x())) best = b;
        }
        return best;
    }

    /** Сбросить до {@code kg} балласта из мешка {@code id} (или из всех по порядку, если id = "all"). @return сброшено, кг */
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
