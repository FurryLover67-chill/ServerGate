package dev.servergate;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

import java.util.regex.Pattern;

/**
 * Скрывает из лога строки вида "Player issued server command: /gate пароль",
 * чтобы общий пароль не попадал в latest.log и консоль.
 */
final class CommandLogFilter extends AbstractFilter {

    private static final Pattern PATTERN = Pattern.compile(
            "issued server command:\\s*/(?:[\\w.-]+:)?(?:gate|gpass)(?:\\s|$)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public Result filter(LogEvent event) {
        return check(event.getMessage());
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        return check(msg);
    }

    private Result check(Message message) {
        if (message == null) return Result.NEUTRAL;
        String text = message.getFormattedMessage();
        return (text != null && PATTERN.matcher(text).find()) ? Result.DENY : Result.NEUTRAL;
    }

    /** Возвращает true, если фильтр удалось установить. */
    static boolean install() {
        try {
            Logger root = (Logger) org.apache.logging.log4j.LogManager.getRootLogger();
            root.addFilter(new CommandLogFilter());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
