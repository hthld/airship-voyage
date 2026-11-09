package com.airshipvoyage.core.structure;

import java.util.Map;
import java.util.Optional;

/**
 * Реестр типов кораблей по ID (ТЗ, 22.3 — «ID типа»). Позже сюда добавятся LZ 129 и LZ 130,
 * загружаемые из файлов чертежей, а не из кода.
 */
public final class ShipLayouts {
    private static final Map<String, ShipLayout> LAYOUTS = Map.of(
            PrototypeFrame.LAYOUT_ID, PrototypeFrame.layout(),
            PrototypeShip.LAYOUT_ID, PrototypeShip.layout());

    public static Optional<ShipLayout> byId(String id) {
        return Optional.ofNullable(LAYOUTS.get(id));
    }

    /** Тип по умолчанию для /airship spawn и для сущности до загрузки данных — летающий прототип. */
    public static ShipLayout defaultLayout() { return PrototypeShip.layout(); }

    public static Iterable<String> ids() { return LAYOUTS.keySet(); }

    /** Модель полёта, укомплектованная по типу корабля (балласт и т.п.). Газ — водород. */
    public static com.airshipvoyage.core.flight.FlightModel newFlightModel(ShipLayout layout) {
        var model = new com.airshipvoyage.core.flight.FlightModel(layout, com.airshipvoyage.core.physics.LiftingGas.HYDROGEN);
        if (layout.id().equals(PrototypeShip.LAYOUT_ID)) {
            model.addBallast(new com.airshipvoyage.core.flight.FlightModel.Ballast("fwd", PrototypeShip.BALLAST_FWD, PrototypeShip.BALLAST_PER_BAG_KG));
            model.addBallast(new com.airshipvoyage.core.flight.FlightModel.Ballast("aft", PrototypeShip.BALLAST_AFT, PrototypeShip.BALLAST_PER_BAG_KG));
        }
        return model;
    }

    private ShipLayouts() {}
}
