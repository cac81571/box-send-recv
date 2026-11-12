package jp.boxsendrecv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class WatchLoop {
    private static final Logger log = LoggerFactory.getLogger(WatchLoop.class);

    private final Config config;
    private final BoxBrowserAutomation automation;
    private final TeamsNotifier notifier;
    private final Set<String> notifiedErrors = new HashSet<>();
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
        if (!config.getTeamsExcludeFolderNames().isEmpty()) {
            log.info(
                "Teams 成功通知の対象外フォルダ: {}",
                String.join(", ", config.getTeamsExcludeFolderNames())
            );
        }
        if (config.getProcessStopFile() != null) {
            log.info("停止ファイル: {}", config.getProcessStopFile());
        }

        while (running) {
            if (consumeStopFile()) {
                log.info("停止ファイルを検知したためプロセスを終了します: {}", config.getProcessStopFile());
                running = false;
                break;
            }
            try {
                List<ProcessedFile> sent = processSends();
                List<ProcessedFile> received = processReceives();
                notifyCycle(sent, received);
            } catch (Exception e) {
                notifyErrorOnce("cycle", "監視処理", null, e);
            }
            sleepQuietly(config.getWatchIntervalSeconds() * 1000L);
        }
    }

    private List<ProcessedFile> processSends() {
        List<ProcessedFile> processed = new ArrayList<>();
        List<Path> files;
        try {
            files = LocalWatch.listSendFiles(config);
            notifiedErrors.remove("send-list");
        } catch (Exception e) {
            notifyErrorOnce("send-list", "送信対象の取得", null, e);
            return processed;
        }
        for (Path file : files) {
            String key = "send:" + file.toAbsolutePath().normalize();
            log.info("送信: {}", file);
            try {
                boolean skipped = automation.uploadAndRename(file);
                processed.add(new ProcessedFile(displayName(file), skipped));
                notifiedErrors.remove(key);
            } catch (Exception e) {
                notifyErrorOnce(key, "送信", file, e);
            }
        }
        return processed;
    }

    private List<ProcessedFile> processReceives() {
        List<ProcessedFile> processed = new ArrayList<>();
        List<Path> files;
        try {
            files = LocalWatch.listReceiveFiles(config);
            notifiedErrors.remove("receive-list");
        } catch (Exception e) {
            notifyErrorOnce("receive-list", "受信対象の取得", null, e);
            return processed;
        }
        for (Path file : files) {
            String key = "receive:" + file.toAbsolutePath().normalize();
            log.info("受信: {}", file);
            try {
                boolean skipped = automation.downloadAndRename(file);
                processed.add(new ProcessedFile(displayName(file), skipped));
                notifiedErrors.remove(key);
            } catch (Exception e) {
                notifyErrorOnce(key, "受信", file, e);
            }
        }
        return processed;
    }

    private boolean consumeStopFile() {
        Path stopFile = config.getProcessStopFile();
        if (stopFile == null || !Files.isRegularFile(stopFile)) {
            return false;
        }
        try {
            Files.deleteIfExists(stopFile);
        } catch (IOException e) {
            log.warn("停止ファイルを削除できませんでした: {} ({})", stopFile, e.getMessage());
        }
        return true;
    }

    private void notifyErrorOnce(String key, String action, Path file, Exception e) {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String target = file == null ? "" : displayName(file);
        if (target.isEmpty()) {
            log.error("{}に失敗しました。監視を継続します: {}", action, detail, e);
        } else {
            log.error("{}に失敗しました。監視を継続します: {} ({})", action, target, detail, e);
        }
        if (notifiedErrors.contains(key)) {
            if (config.isTeamsNotificationEnabled()) {
                log.info("同じファイルの失敗が続いているため Teams 通知は省略しました: {}", target.isEmpty() ? action : target);
            }
            return;
        }
        String text = target.isEmpty()
            ? action + "に失敗しました: " + detail
            : action + "に失敗しました: " + target + " (" + detail + ")";
        try {
            notifier.sendError(text);
            if (config.isTeamsNotificationEnabled()) {
                notifiedErrors.add(key);
            }
        } catch (Exception notifyError) {
            log.warn("Teams エラー通知の送信に失敗しました: {}", notifyError.getMessage());
        }
    }

    private void notifyCycle(List<ProcessedFile> sent, List<ProcessedFile> received) {
        try {
            notifier.sendSentFiles(filesForTeamsSuccess(sent), config.getBoxFolderUrl());
            notifier.sendReceivedFiles(filesForTeamsSuccess(received), config.getLocalWatchPath());
        } catch (Exception e) {
            log.warn("Teams 通知の送信に失敗しました: {}", e.getMessage());
        }
    }

    private List<ProcessedFile> filesForTeamsSuccess(List<ProcessedFile> files) {
        if (!config.isTeamsNotificationEnabled() || !config.isTeamsNotifySuccess()) {
            return files;
        }
        List<ProcessedFile> included = new ArrayList<>();
        for (ProcessedFile file : files) {
            if (config.isMonitoringFolderFile(file.fileName())) {
                log.info("監視用フォルダのため Teams 成功通知を省略しました: {}", file.fileName());
                continue;
            }
            included.add(file);
        }
        return included;
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
