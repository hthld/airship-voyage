package com.airshipvoyage.core.rigid;

import com.airshipvoyage.core.math.Mat3;
import com.airshipvoyage.core.math.Quat;
import com.airshipvoyage.core.math.Vec3d;

/**
 * Твёрдое тело с шестью степенями свободы — корабль во время нормального полёта (ТЗ, 16.8, 22.5).
 *
 * <p>Состояние: позиция <b>начала локальных координат корпуса</b> в мире, ориентация,
 * линейная скорость центра масс и угловой момент. Хранится именно угловой момент L,
 * а не ω: при свободном вращении он сохраняется точно, и интегратор не «раскачивает»
 * длинное тело (у дирижабля моменты инерции по осям различаются в десятки раз).
 *
 * <p>Использование за тик: {@code applyForce…/applyTorque} → {@code step(dt)}.
 * Силы сбрасываются после шага. Единицы — СИ, мировые оси Minecraft (Y вверх).
 */
public final class RigidBody {
    private MassProperties mass;
    private Mat3 inertiaInv;

    /** Позиция начала локальных координат корпуса в мире, м. */
    private Vec3d position = Vec3d.ZERO;
    private Quat orientation = Quat.IDENTITY;
    /** Скорость центра масс, м/с. */
    private Vec3d velocity = Vec3d.ZERO;
    /** Угловой момент в мировых осях, кг·м²/с. */
    private Vec3d angularMomentum = Vec3d.ZERO;

    /**
     * Присоединённая масса воздуха по осям корпуса, кг (крен-ось X, вертикаль Y, борт Z): воздух, который
     * корпус вынужден разгонять вместе с собой. У дирижабля поперёк оси она сравнима с массой самого
     * корабля, вдоль оси мала. Влияет только на разгон от сил, не на вес.
     */
    private Vec3d addedMassLocal = Vec3d.ZERO;

    private Vec3d forceAccum = Vec3d.ZERO;
    private Vec3d torqueAccum = Vec3d.ZERO;

    public RigidBody(MassProperties mass) {
        setMassProperties(mass);
    }

    /**
     * Масса меняется по ходу полёта (топливо, балласт, газ, груз, отделение деталей) — без импульса:
     * поле скоростей корпуса не меняется. Угловая скорость сохраняется, а скорость нового центра масс
     * берётся из прежнего движения корпуса в этой точке: v' = v + ω × (ЦМ' − ЦМ).
     * Добавление груза, движущегося с другой скоростью, требует отдельного расчёта импульса.
     */
    public void setMassProperties(MassProperties mp) {
        Vec3d omega = Vec3d.ZERO;
        if (this.mass != null) {
            omega = angularVelocity();
            Vec3d comShiftWorld = orientation.rotate(mp.centerOfMass().sub(this.mass.centerOfMass()));
            velocity = velocity.add(omega.cross(comShiftWorld));
        }
        this.mass = mp;
        this.inertiaInv = mp.inertia().inverse();
        this.angularMomentum = worldInertia().transform(omega);
    }

    // ------------------------------------------------------------------ преобразования

    public Vec3d localToWorld(Vec3d local) { return position.add(orientation.rotate(local)); }
    public Vec3d worldToLocal(Vec3d world) { return orientation.inverseRotate(world.sub(position)); }
    public Vec3d centerOfMassWorld() { return localToWorld(mass.centerOfMass()); }

    /** Тензор инерции в мировых осях: R·I·Rᵀ. */
    public Mat3 worldInertia() { return mass.inertia().rotatedBy(orientation.toMatrix()); }

    private Mat3 worldInertiaInv() { return inertiaInv.rotatedBy(orientation.toMatrix()); }

    /** Угловая скорость в мировых осях, рад/с. */
    public Vec3d angularVelocity() { return worldInertiaInv().transform(angularMomentum); }

    /** Скорость точки корпуса (мировые координаты) — нужна для «привязки» игрока и для ударов. */
    public Vec3d velocityAt(Vec3d worldPoint) {
        return velocity.add(angularVelocity().cross(worldPoint.sub(centerOfMassWorld())));
    }

    // ------------------------------------------------------------------ силы

    /** Сила, приложенная в центре масс. */
    public void applyCentralForce(Vec3d force) { forceAccum = forceAccum.add(force); }

    /** Сила в произвольной точке (мировые координаты) — даёт ещё и момент. */
    public void applyForceAt(Vec3d force, Vec3d worldPoint) {
        forceAccum = forceAccum.add(force);
        torqueAccum = torqueAccum.add(worldPoint.sub(centerOfMassWorld()).cross(force));
    }

    /** Сила в точке, заданной в локальных координатах корпуса. */
    public void applyForceAtLocal(Vec3d force, Vec3d localPoint) {
        applyForceAt(force, localToWorld(localPoint));
    }

    public void applyTorque(Vec3d torque) { torqueAccum = torqueAccum.add(torque); }

    /** Мгновенный импульс в точке (удар). */
    public void applyImpulseAt(Vec3d impulse, Vec3d worldPoint) {
        velocity = velocity.add(accelerationFrom(impulse));
        angularMomentum = angularMomentum.add(worldPoint.sub(centerOfMassWorld()).cross(impulse));
    }

    // ------------------------------------------------------------------ интегрирование

    /** Полуявный Эйлер: сначала скорости, потом положение. Устойчив при шаге тика 1/20 с. */
    public void step(double dt) {
        if (dt <= 0) return;
        Vec3d com = centerOfMassWorld();

        velocity = velocity.addScaled(accelerationFrom(forceAccum), dt);
        angularMomentum = angularMomentum.addScaled(torqueAccum, dt);

        Vec3d newCom = com.addScaled(velocity, dt);
        orientation = integrateOrientation(orientation, angularMomentum, dt);
        // Начало локальных координат «подвешено» к центру масс: при вращении вокруг ЦМ оно смещается.
        position = newCom.sub(orientation.rotate(mass.centerOfMass()));

        forceAccum = Vec3d.ZERO;
        torqueAccum = Vec3d.ZERO;

        if (!position.isFinite() || !orientation.isFinite() || !velocity.isFinite() || !angularMomentum.isFinite()) {
            throw new IllegalStateException("rigid body diverged");
        }
    }

    /** a = F / (m + mₐ) по каждой оси корпуса. */
    private Vec3d accelerationFrom(Vec3d forceWorld) {
        if (addedMassLocal.equals(Vec3d.ZERO)) return forceWorld.scale(1.0 / mass.massKg());
        Vec3d f = orientation.inverseRotate(forceWorld);
        double m = mass.massKg();
        Vec3d a = new Vec3d(f.x() / (m + addedMassLocal.x()), f.y() / (m + addedMassLocal.y()), f.z() / (m + addedMassLocal.z()));
        return orientation.rotate(a);
    }

    /** Эффективная масса для разгона вдоль мировой вертикали, кг (для оценок жёсткости контакта). */
    public double effectiveVerticalMassKg() {
        Vec3d up = orientation.inverseRotate(Vec3d.UNIT_Y);
        double m = mass.massKg();
        return m + up.x() * up.x() * addedMassLocal.x() + up.y() * up.y() * addedMassLocal.y() + up.z() * up.z() * addedMassLocal.z();
    }

    /**
     * Рунге–Кутта 4-го порядка для q' = ½·ω(q)·q, где ω(q) = R(q)·I⁻¹·R(q)ᵀ·L.
     * Явный шаг Эйлера здесь непригоден: у вытянутого корпуса (Iкрен ≪ Iкурс) он за минуту
     * свободного вращения «накачивает» энергию в разы — найдено тестом.
     */
    private Quat integrateOrientation(Quat q, Vec3d l, double dt) {
        Quat k1 = q.derivative(omegaFor(q, l));
        Quat k2 = q.addScaled(k1, dt / 2).derivative(omegaFor(q.addScaled(k1, dt / 2), l));
        Quat k3 = q.addScaled(k2, dt / 2).derivative(omegaFor(q.addScaled(k2, dt / 2), l));
        Quat k4 = q.addScaled(k3, dt).derivative(omegaFor(q.addScaled(k3, dt), l));
        return new Quat(
                q.w() + dt / 6 * (k1.w() + 2 * k2.w() + 2 * k3.w() + k4.w()),
                q.x() + dt / 6 * (k1.x() + 2 * k2.x() + 2 * k3.x() + k4.x()),
                q.y() + dt / 6 * (k1.y() + 2 * k2.y() + 2 * k3.y() + k4.y()),
                q.z() + dt / 6 * (k1.z() + 2 * k2.z() + 2 * k3.z() + k4.z())).normalized();
    }

    private Vec3d omegaFor(Quat q, Vec3d l) {
        return inertiaInv.rotatedBy(q.normalized().toMatrix()).transform(l);
    }

    /** Остановить всякое движение (швартовка, команды отладки). */
    public void freeze() {
        velocity = Vec3d.ZERO;
        angularMomentum = Vec3d.ZERO;
        forceAccum = Vec3d.ZERO;
        torqueAccum = Vec3d.ZERO;
    }

    /** Кинетическая энергия, Дж — для проверки интегратора и оценки силы удара. */
    public double kineticEnergy() {
        Vec3d omega = angularVelocity();
        return 0.5 * mass.massKg() * velocity.lengthSquared() + 0.5 * omega.dot(angularMomentum);
    }

    // ------------------------------------------------------------------ состояние

    public MassProperties massProperties() { return mass; }
    public double massKg() { return mass.massKg(); }
    public Vec3d position() { return position; }
    public Quat orientation() { return orientation; }
    public Vec3d velocity() { return velocity; }
    public Vec3d angularMomentum() { return angularMomentum; }
    public Vec3d addedMassLocal() { return addedMassLocal; }
    public void setAddedMassLocal(Vec3d addedMass) { this.addedMassLocal = addedMass; }

    public void setPosition(Vec3d p) { this.position = p; }

    /** Сменить ориентацию, сохранив угловую скорость. */
    public void setOrientation(Quat q) {
        Vec3d omega = angularVelocity();
        this.orientation = q.normalized();
        this.angularMomentum = worldInertia().transform(omega);
    }

    public void setVelocity(Vec3d v) { this.velocity = v; }

    public void setAngularVelocity(Vec3d omegaWorld) {
        this.angularMomentum = worldInertia().transform(omegaWorld);
    }
}
