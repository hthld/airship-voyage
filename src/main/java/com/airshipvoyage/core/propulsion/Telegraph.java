package com.airshipvoyage.core.propulsion;

import java.util.Locale;
import java.util.Optional;

/**
 * Машинный телеграф (ТЗ 8.4, 9.2): ступени хода, которые передаются на мотор. Значение — доля мощности
 * со знаком: «вперёд» > 0, «назад» < 0.
 */
public enum Telegraph {
    FULL_ASTERN(-1.0),
    HALF_ASTERN(-0.5),
    SLOW_ASTERN(-0.25),
    STOP(0.0),
    SLOW(0.25),
    HALF(0.5),
    FULL(1.0);

    /** Доля мощности со знаком. */
    public final double power;

    Telegraph(double power) {
        this.power = power;
    }

    /** Имя для команд и сохранения: {@code full_astern}, {@code stop}, {@code half}… */
    public String key() { return name().toLowerCase(Locale.ROOT); }

    /** Соседняя ступень: +1 — на ступень вперёд, −1 — назад; на краях остаётся. Для поста управления (ТЗ 9.2). */
    public Telegraph step(int direction) {
        int i = Math.max(0, Math.min(values().length - 1, ordinal() + Integer.signum(direction)));
        return values()[i];
    }

    public static Optional<Telegraph> byKey(String key) {
        for (Telegraph t : values()) if (t.key().equals(key)) return Optional.of(t);
        return Optional.empty();
    }
}
