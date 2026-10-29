package jp.boxsendrecv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class TeamsNotifier {
    private static final Logger log = LoggerFactory.getLogger(TeamsNotifier.class);
    private static final DateTimeFormatter EXECUTED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final boolean enabled;
    private final String webhookUrl;
    private final String sendSuccessTitle;
    private final String receiveSuccessTitle;
    private final String genericErrorTitle;
    private final OkHttpClient client = new OkHttpClient();

    public TeamsNotifier(Config config) {
        this.enabled = config.isTeamsNotificationEnabled();
        this.webhookUrl = config.getTeamsWebhookUrl();
        this.sendSuccessTitle = config.getTeamsNotificationTitleSendSuccess();
        this.receiveSuccessTitle = config.getTeamsNotificationTitleReceiveSuccess();
        this.genericErrorTitle = config.getTeamsNotificationTitleError();
        if (!enabled) {
            log.info("Teams 通知は無効です (teams.notification.enabled=false)");
        }
    }

    public void sendSentFiles(List<ProcessedFile> files, String boxFolderLabel) throws IOException {
        notifyFiles(files, sendSuccessTitle, "Box 送信先: " + boxFolderLabel);
    }

    public void sendReceivedFiles(List<ProcessedFile> files, Path receivedPath) throws IOException {
        notifyFiles(files, receiveSuccessTitle, "保存先: " + PathDisplay.forTeams(receivedPath));
    }

    public void sendError(String errorMessage) throws IOException {
        sendPayload(buildErrorAdaptiveCardJson(genericErrorTitle, errorMessage), 0, true);
    }

    private void notifyFiles(List<ProcessedFile> files, String title, String pathText) throws IOException {
        if (files == null || files.isEmpty()) {
            return;
        }
        sendPayload(buildAdaptiveCardJson(title, "処理件数: " + files.size() + " 件", pathText, files), files.size(), false);
    }

    private String buildErrorAdaptiveCardJson(String title, String errorMessage) {
        return "{"
            + "\"$schema\":\"http://adaptivecards.io/schemas/adaptive-card.json\","
            + "\"type\":\"AdaptiveCard\","
            + "\"version\":\"1.4\","
            + "\"body\":["
            + "{\"type\":\"TextBlock\",\"text\":\"" + escapeJson(title) + "\",\"weight\":\"Bolder\",\"size\":\"Large\",\"color\":\"Attention\"},"
            + "{\"type\":\"TextBlock\",\"text\":\"" + escapeJson(errorMessage) + "\",\"wrap\":true,\"color\":\"Attention\"}"
            + "]"
            + "}";
    }

    private void sendPayload(String payload, int count, boolean error) throws IOException {
        if (!enabled) {
            return;
        }

        Request request = new Request.Builder()
            .url(webhookUrl)
            .post(RequestBody.create(payload, JSON))
            .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException(
                    "Teams Webhook の送信に失敗しました: HTTP " + response.code() + " " + response.message()
                );
            }
            if (error) {
                log.info("Teams エラー通知を送信しました");
            } else {
                log.info("Teams 通知を送信しました ({} 件)", count);
            }
        }
    }

    private String buildAdaptiveCardJson(String title, String countText, String pathText, List<ProcessedFile> files) {
        StringBuilder fileList = new StringBuilder();
        for (ProcessedFile file : files) {
            String line = file.fileName();
            if (file.skipped()) {
                line += " （スキップ）";
            }
            fileList.append("- ").append(escapeJson(line)).append("\\n");
        }

        return "{"
            + "\"$schema\":\"http://adaptivecards.io/schemas/adaptive-card.json\","
            + "\"type\":\"AdaptiveCard\","
            + "\"version\":\"1.4\","
            + "\"body\":["
            + "{\"type\":\"TextBlock\",\"text\":\"" + escapeJson(title) + "\",\"weight\":\"Bolder\",\"size\":\"Large\"},"
            + "{\"type\":\"TextBlock\",\"text\":\"" + escapeJson(countText) + "\",\"wrap\":true},"
            + "{\"type\":\"TextBlock\",\"text\":\"" + escapeJson(pathText) + "\",\"wrap\":true},"
            + "{\"type\":\"TextBlock\",\"text\":\"" + fileList + "\",\"wrap\":true,\"fontType\":\"Monospace\"},"
            + "{\"type\":\"TextBlock\",\"text\":\"実行日時: " + escapeJson(LocalDateTime.now().format(EXECUTED_AT_FORMAT)) + "\",\"isSubtle\":true,\"wrap\":true}"
            + "]"
            + "}";
    }

    private String escapeJson(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
