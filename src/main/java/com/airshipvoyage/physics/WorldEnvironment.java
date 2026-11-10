package com.airshipvoyage.physics;

import com.airshipvoyage.config.AirshipConfig;
import com.airshipvoyage.core.flight.CollisionBoxes;
import com.airshipvoyage.core.flight.FlightModel;
import com.airshipvoyage.core.math.Aabb;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.physics.AltitudeMapper;
import com.airshipvoyage.core.physics.Atmosphere;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * Мир Minecraft для физического ядра: высота над уровнем моря (настройки ТЗ 8.8) и твёрдые блоки
 * как опора для контакта корабля с землёй (ТЗ, 13.3, пункт 9 прототипа).
 */
public final class WorldEnvironment implements FlightModel.Environment, FlightModel.ContactQuery {
    private final Level level;
    private final AltitudeMapper mapper;

    public WorldEnvironment(Level level) {
        this.level = level;
        this.mapper = new AltitudeMapper(AirshipConfig.SEA_LEVEL_Y.get(), AirshipConfig.ALTITUDE_METERS_PER_BLOCK.get());
    }

    @Override public double altitudeMeters(double worldY) { return mapper.altitudeMeters(worldY); }
    @Override public Atmosphere atmosphere() { return Atmosphere.STANDARD; }
    @Override public FlightModel.ContactQuery contacts() { return this; }

    /**
     * Ветер в проверках (GameTest): null — по умолчанию; на сервере проверок по умолчанию штиль, чтобы старые
     * проверки положения не «сдувало». Тест ветра задаёт его явно и сбрасывает.
     */
    public static volatile Vec3d testWind;

    /** Ветер (ТЗ 8.6, AV-039): поле {@link WindField} от зерна мира, времени, высоты и погоды; сила — из настроек. */
    @Override
    public Vec3d wind(Vec3d p) {
        if (testWind != null) return testWind;
        if (level.isClientSide || level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) return Vec3d.ZERO;
        long seed = level instanceof net.minecraft.server.level.ServerLevel sl ? sl.getSeed() : 0;
        double weather = 1 + 0.5 * level.getRainLevel(1) + 1.0 * level.getThunderLevel(1);
        return new com.airshipvoyage.core.physics.WindField(seed).wind(p.x(), p.z(), altitudeMeters(p.y()),
                level.getGameTime() / 20.0, weather, AirshipConfig.WIND_INTENSITY.get());
    }

    // ------------------------------------------------------------------ контакт с блоками

    /**
     * Снимок коллизионных коробок блоков в области на один тик. Обход — ванильный ({@code getBlockCollisions}):
     * он учитывает формы, выходящие за свою клетку (забор, стена — 1,5 блока), и пропускает незагруженные чанки
     * (ревью 2, №7: раньше точка проверялась только по блоку своей клетки, и верх забора был «пустотой»).
     */
    @Override
    public FlightModel.ContactQuery snapshot(Aabb region) {
        List<Aabb> boxes = new ArrayList<>();
        AABB area = new AABB(region.min().x(), region.min().y(), region.min().z(),
                region.max().x(), region.max().y(), region.max().z());
        for (VoxelShape shape : level.getBlockCollisions(null, area)) {
            for (AABB b : shape.toAabbs()) {
                boxes.add(new Aabb(new Vec3d(b.minX, b.minY, b.minZ), new Vec3d(b.maxX, b.maxY, b.maxZ)));
            }
        }
        return new CollisionBoxes(boxes);
    }

    /** Есть ли в области хоть один блок с коллизией. В полёте не используется — корабль берёт снимок на тик. */
    @Override
    public boolean mayContact(Aabb b) {
        return snapshot(b).mayContact(b);
    }

    /** Вход точки в блоки — по снимку вокруг неё (для отладки и тестов; в полёте — снимок на тик). */
    @Override
    public FlightModel.Penetration probe(Vec3d p) {
        return snapshot(new Aabb(p, p).inflate(1.5, 1.5, 1.5)).probe(p);
    }

    @Override
    public List<Aabb> boxesIn(Aabb region) {
        return snapshot(region).boxesIn(region);
    }
}
