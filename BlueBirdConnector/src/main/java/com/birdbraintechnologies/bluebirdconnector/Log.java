package com.birdbraintechnologies.bluebirdconnector;

import java.util.logging.*;

public class Log {
    private final Logger logger;

    public static Log getLogger(Class<?> cls) {
        return new Log(cls);
    }

    public Log(Class<?> cls) {
        logger = Logger.getLogger(cls.getName());
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
        if (logger.isLoggable(Level.FINE)) logger.fine(format(fmt, args));
    }

    private String format(String fmt, Object[] args) {
        String[] parts = fmt.split("\\{}", -1); // -1 keeps trailing empty part
        StringBuilder sb = new StringBuilder();

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

}

