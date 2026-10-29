package jp.boxsendrecv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class WatchLoop {
    private static final Logger log = LoggerFactory.getLogger(WatchLoop.class);

    private final Config config;
    private final BoxBrowserAutomation automation;
    private final TeamsNotifier notifier;
    private volatile boolean running = true;

    WatchLoop(Config config, BoxBrowserAutomation automation, TeamsNotifier notifier) {
        this.config = config;
        this.automation = automation;
        this.notifier = notifier;
    }

    void stop() {
        running = false;
    }

    void run() throws Exception {
        automation.startSession();
        log.info(
            "監視開始: 間隔 {} 秒 / 送信接頭辞 {} / 受信接頭辞 {} / ローカル {}",
            config.getWatchIntervalSeconds(),
            config.getSendPrefix(),
            config.getReceivePrefix(),
            config.getLocalWatchPath().toAbsolutePath()
        );

        while (running) {
            List<ProcessedFile> sent = processSends();
            List<ProcessedFile> received = processReceives();
            notifyCycle(sent, received);
            sleepQuietly(config.getWatchIntervalSeconds() * 1000L);
        }
    }

    private List<ProcessedFile> processSends() throws Exception {
        List<ProcessedFile> processed = new ArrayList<>();
        for (Path file : LocalWatch.listSendFiles(config)) {
            log.info("送信: {}", file);
            boolean skipped = automation.uploadAndRename(file);
            processed.add(new ProcessedFile(displayName(file), skipped));
        }
        return processed;
    }

    private List<ProcessedFile> processReceives() throws Exception {
        List<ProcessedFile> processed = new ArrayList<>();
        for (Path file : LocalWatch.listReceiveFiles(config)) {
            log.info("受信: {}", file);
            boolean skipped = automation.downloadAndRename(file);
            processed.add(new ProcessedFile(displayName(file), skipped));
        }
        return processed;
    }

    private void notifyCycle(List<ProcessedFile> sent, List<ProcessedFile> received) {
        try {
            notifier.sendSentFiles(sent, config.getBoxFolderUrl());
            notifier.sendReceivedFiles(received, config.getLocalWatchPath());
        } catch (Exception e) {
            log.warn("Teams 通知の送信に失敗しました: {}", e.getMessage());
        }
    }

    private String displayName(Path file) {
        try {
            return config.getLocalWatchPath().relativize(file).toString().replace('\\', '/');
        } catch (Exception e) {
            return file.getFileName().toString();
        }
    }

    private void sleepQuietly(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (running && System.currentTimeMillis() < deadline) {
            long remain = deadline - System.currentTimeMillis();
            try {
                Thread.sleep(Math.min(500, Math.max(0, remain)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
                return;
            }
        }
    }
}
