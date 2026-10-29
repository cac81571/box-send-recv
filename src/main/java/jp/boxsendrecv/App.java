package jp.boxsendrecv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    public static void main(String[] args) {
        String configPath = null;
        Config config = null;
        TeamsNotifier notifier = null;
        Logger log = null;

        try {
            for (String arg : args) {
                if (arg.startsWith("--config=")) {
                    configPath = arg.substring("--config=".length());
                }
            }

            LoggingBootstrap.init(configPath);
            log = LoggerFactory.getLogger(App.class);
            config = Config.load(configPath);
            notifier = new TeamsNotifier(config);

            log.info("モード: 送受信監視");
            log.info("監視フォルダ: {}", config.getLocalWatchPath().toAbsolutePath());
            log.info("Box: {}", config.getBoxFolderUrl());
            log.info("接頭辞: 送信={}, 受信={}", config.getSendPrefix(), config.getReceivePrefix());

            BoxBrowserAutomation automation = new BoxBrowserAutomation(config);
            WatchLoop loop = new WatchLoop(config, automation, notifier);
            Runtime.getRuntime().addShutdownHook(new Thread(loop::stop, "watch-stop"));
            try {
                loop.run();
            } finally {
                automation.close();
            }
        } catch (Exception e) {
            if (log == null) {
                log = LoggerFactory.getLogger(App.class);
            }
            log.error("エラー: {}", e.getMessage(), e);
            if (notifier != null) {
                try {
                    notifier.sendError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                } catch (Exception notifyError) {
                    log.warn("Teams エラー通知の送信に失敗しました: {}", notifyError.getMessage());
                }
            }
            System.exit(1);
        }
    }
}
