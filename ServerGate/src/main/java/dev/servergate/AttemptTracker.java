package dev.servergate;

import java.util.HashMap;
import java.util.Map;

/** Считает неверные попытки по IP (переподключение счётчик не сбрасывает). */
final class AttemptTracker {

    private final Map<String, Integer> fails = new HashMap<>();
    private final Map<String, Long> blockedUntil = new HashMap<>();

    /** Сколько миллисекунд IP ещё заблокирован (0 - не заблокирован). */
    synchronized long blockedMillisLeft(String ip) {
        Long until = blockedUntil.get(ip);
        if (until == null) return 0;
        long left = until - System.currentTimeMillis();
        if (left <= 0) {
            blockedUntil.remove(ip);
            fails.remove(ip);
            return 0;
        }
        return left;
    }

    /** Регистрирует неудачу. Возвращает, сколько попыток осталось (0 = IP заблокирован). */
    synchronized int registerFailure(String ip, int maxAttempts, long lockoutMillis) {
        int n = fails.merge(ip, 1, Integer::sum);
        if (n >= maxAttempts) {
            blockedUntil.put(ip, System.currentTimeMillis() + lockoutMillis);
            fails.remove(ip);
            return 0;
        }
        return maxAttempts - n;
    }

    synchronized void clear(String ip) {
        fails.remove(ip);
        blockedUntil.remove(ip);
    }
}
