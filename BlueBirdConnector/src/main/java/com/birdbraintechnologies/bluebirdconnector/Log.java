package com.birdbraintechnologies.bluebirdconnector;

import java.util.logging.*;

public class Log {
    private final Logger logger;
    private final String label;


    public static Log getLogger(Class<?> cls) {
        return new Log(cls);
    }

    public Log(Class<?> cls) {
        logger = Logger.getLogger(cls.getName());
        label = cls.getSimpleName();
    }

    public void info(String fmt, Object... args) {
        if (logger.isLoggable(Level.INFO)) logger.info(format(fmt, args));
    }

    public void warn(String fmt, Object... args) {
        if (logger.isLoggable(Level.WARNING)) logger.warning(format(fmt, args));
    }

    public void error(String fmt, Object... args) {
        if (logger.isLoggable(Level.SEVERE)) logger.severe(format(fmt, args));
    }

    public void debug(String fmt, Object... args) {
        if (logger.isLoggable(Level.FINE)) logger.info(format(fmt, args));
    }

    private String format(String fmt, Object[] args) {
        String[] parts = fmt.split("\\{}", -1); // -1 keeps trailing empty part
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        sb.append(label);
        sb.append("] ");

        for (int i = 0; i < parts.length - 1; i++) {
            sb.append(parts[i]);
            if (i < args.length) {
                sb.append(String.valueOf(args[i]));
            } else {
                sb.append("{}"); // unmatched placeholder
            }
        }
        sb.append(parts[parts.length - 1]);
        return sb.toString();
    }

    public static class OneLineFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            return String.format(
                    "[%1$tF %1$tT] [%2$-5s] %3$s%n",
                    record.getMillis(),
                    levelName(record.getLevel()),
                    formatMessage(record));
        }

        public static String levelName(Level level) {
            int val = level.intValue();
            if (val >= Level.SEVERE.intValue()) return "ERROR";
            if (val >= Level.WARNING.intValue()) return "WARN";
            if (val >= Level.INFO.intValue()) return "INFO";
            if (val >= Level.CONFIG.intValue()) return "DEBUG";
            if (val >= Level.FINEST.intValue()) return "TRACE";
            return level.getName();
        }
    }

    static final Formatter ONE_LINE_FORMATTER = new OneLineFormatter();

    static {
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            handler.setFormatter(ONE_LINE_FORMATTER);
        }
    }
}
