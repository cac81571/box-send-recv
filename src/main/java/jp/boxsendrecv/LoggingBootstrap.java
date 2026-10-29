package jp.boxsendrecv;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public final class LoggingBootstrap {
    private LoggingBootstrap() {
    }

    public static void init(String configPath) {
        Properties properties = new Properties();

        try (InputStream stream = openConfigStream(configPath)) {
            if (stream != null) {
                try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
            }
        } catch (Exception ignored) {
        }

        String logPath = properties.getProperty("logging.file.path", "./logs").trim();
        String logLevel = properties.getProperty("logging.level", "INFO").trim();
        String consoleCharset = properties.getProperty("logging.console.charset", "UTF-8").trim();

        if (!logPath.isEmpty()) {
            System.setProperty("LOG_PATH", logPath);
            new File(logPath).mkdirs();
        }
        if (!logLevel.isEmpty()) {
            System.setProperty("LOG_LEVEL", logLevel);
        }
        if (!consoleCharset.isEmpty()) {
            System.setProperty("LOG_CONSOLE_CHARSET", consoleCharset);
        }

        reconfigure();
    }

    private static void reconfigure() {
        try {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            context.reset();
            try (InputStream in = LoggingBootstrap.class.getClassLoader().getResourceAsStream("logback.xml")) {
                if (in != null) {
                    configurator.doConfigure(in);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static InputStream openConfigStream(String configPath) throws Exception {
        if (configPath != null) {
            File file = new File(configPath);
            if (file.exists()) {
                return new FileInputStream(file);
            }
        }

        InputStream stream = LoggingBootstrap.class.getClassLoader().getResourceAsStream("application.properties");
        if (stream != null) {
            return stream;
        }

        return LoggingBootstrap.class.getClassLoader().getResourceAsStream("application.properties.example");
    }
}
