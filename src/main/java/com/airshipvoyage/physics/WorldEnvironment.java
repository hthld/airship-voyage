package com.airshipvoyage.physics;

import com.airshipvoyage.config.AirshipConfig;
import com.airshipvoyage.core.flight.FlightModel;
import com.airshipvoyage.core.math.Aabb;
import com.airshipvoyage.core.math.Vec3d;
import com.airshipvoyage.core.physics.AltitudeMapper;
import com.airshipvoyage.core.physics.Atmosphere;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Мир Minecraft для физического ядра: высота над уровнем моря (настройки ТЗ 8.8) и твёрдые блоки
 * как опора для контакта корабля с землёй (ТЗ, 13.3, пункт 9 прототипа).
 */
public final class WorldEnvironment implements FlightModel.Environment, FlightModel.ContactQuery {
    private final Level level;
    private final AltitudeMapper mapper;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public WorldEnvironment(Level level) {
        this.level = level;
        this.mapper = new AltitudeMapper(AirshipConfig.SEA_LEVEL_Y.get(), AirshipConfig.ALTITUDE_METERS_PER_BLOCK.get());
    }

    @Override public double altitudeMeters(double worldY) { return mapper.altitudeMeters(worldY); }
    @Override public Atmosphere atmosphere() { return Atmosphere.STANDARD; }
    @Override public FlightModel.ContactQuery contacts() { return this; }

    // ------------------------------------------------------------------ контакт с блоками

    /** Есть ли в области хоть один блок с коллизией (быстрая отсечка; незагруженные чанки — пустота). */
    @Override
    public boolean mayContact(Aabb b) {
        int x0 = Mth.floor(b.min().x()), y0 = Mth.floor(b.min().y()), z0 = Mth.floor(b.min().z());
        int x1 = Mth.floor(b.max().x()), y1 = Mth.floor(b.max().y()), z1 = Mth.floor(b.max().z());
        y0 = Math.max(y0, level.getMinBuildHeight());
        y1 = Math.min(y1, level.getMaxBuildHeight() - 1);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                for (int y = y0; y <= y1; y++) {
                    cursor.set(x, y, z);
                    BlockState st = level.getBlockState(cursor);
                    if (!st.isAir() && !st.getCollisionShape(level, cursor).isEmpty()) return true;
                }
            }
        }
        return false;
    }

    /**
     * Глубина входа точки в коллизию блока и направление выталкивания. Учитываются только «открытые» грани:
     * если с той стороны стоит полный блок, выталкивать туда бессмысленно — так точка, ушедшая в землю,
     * выталкивается вверх, а не вбок в соседний блок.
     */
    @Override
    public FlightModel.Penetration probe(Vec3d p) {
        int bx = Mth.floor(p.x()), by = Mth.floor(p.y()), bz = Mth.floor(p.z());
        if (!level.hasChunk(bx >> 4, bz >> 4)) return null;
        cursor.set(bx, by, bz);
        BlockState st = level.getBlockState(cursor);
        if (st.isAir()) return null;
        VoxelShape shape = st.getCollisionShape(level, cursor);
        if (shape.isEmpty()) return null;

        for (AABB local : shape.toAabbs()) {
            AABB box = local.move(bx, by, bz);
            if (!box.contains(p.x(), p.y(), p.z())) continue;
            double best = Double.MAX_VALUE;
            Direction bestDir = null;
            for (Direction d : Direction.values()) {
                double depth = switch (d) {
                    case UP -> box.maxY - p.y();
                    case DOWN -> p.y() - box.minY;
                    case EAST -> box.maxX - p.x();
                    case WEST -> p.x() - box.minX;
                    case SOUTH -> box.maxZ - p.z();
                    case NORTH -> p.z() - box.minZ;
                };
                if (depth >= best || faceCovered(bx, by, bz, d, box)) continue;
                best = depth;
                bestDir = d;
            }
            if (bestDir == null) {
                // точка глубоко в сплошном массиве: выталкиваем вверх к ближайшей открытой поверхности
                return new FlightModel.Penetration(Vec3d.UNIT_Y, Math.min(box.maxY - p.y(), 0.5));
            }
            var n = bestDir.getNormal();
            return new FlightModel.Penetration(new Vec3d(n.getX(), n.getY(), n.getZ()), best);
        }
        return null;
    }

    /** Грань закрыта, если коробка доходит до края блока, а соседний блок за ней — полный. */
    private boolean faceCovered(int bx, int by, int bz, Direction d, AABB box) {
        boolean atEdge = switch (d) {
            case UP -> box.maxY >= by + 1 - 1e-6;
            case DOWN -> box.minY <= by + 1e-6;
            case EAST -> box.maxX >= bx + 1 - 1e-6;
            case WEST -> box.minX <= bx + 1e-6;
            case SOUTH -> box.maxZ >= bz + 1 - 1e-6;
            case NORTH -> box.minZ <= bz + 1e-6;
        };
        if (!atEdge) return false;
        BlockPos n = new BlockPos(bx + d.getStepX(), by + d.getStepY(), bz + d.getStepZ());
        BlockState ns = level.getBlockState(n);
        return !ns.isAir() && ns.isCollisionShapeFullBlock(level, n);
    }
}
