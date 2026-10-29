package jp.boxsendrecv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

public class Config {
    private static final Logger log = LoggerFactory.getLogger(Config.class);

    private final String boxFolderUrl;
    private final String boxEnterpriseDomain;
    private final Path localWatchPath;
    private final String sendPrefix;
    private final String receivePrefix;
    private final int watchIntervalSeconds;
    private final Set<String> fileExtensions;
    private final boolean teamsNotificationEnabled;
    private final String teamsWebhookUrl;
    private final String teamsNotificationTitleSendSuccess;
    private final String teamsNotificationTitleReceiveSuccess;
    private final String teamsNotificationTitleError;
    private final String browserCdpUrl;
    private final int browserSlowMo;
    private final int browserTimeoutMs;
    private final int browserTimeoutRetryCount;
    private final int uploadCompleteTimeoutMs;
    private final int boxLoginWaitSeconds;

    public Config(Properties properties) {
        String extensions = properties.getProperty("file.extensions", "").trim();
        if (extensions.isEmpty()) {
            fileExtensions = Set.of();
        } else {
            fileExtensions = Arrays.stream(extensions.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        teamsNotificationEnabled = Boolean.parseBoolean(
            properties.getProperty("teams.notification.enabled", "false")
        );
        if (teamsNotificationEnabled) {
            teamsWebhookUrl = require(properties, "teams.webhook.url");
        } else {
            String webhookUrl = optional(properties, "teams.webhook.url");
            teamsWebhookUrl = webhookUrl != null ? webhookUrl : "";
        }
        teamsNotificationTitleSendSuccess = propertyWithDefault(
            properties, "teams.notification.title.send.success", "Box ファイル送信完了"
        );
        teamsNotificationTitleReceiveSuccess = propertyWithDefault(
            properties, "teams.notification.title.receive.success", "Box ファイル受信完了"
        );
        teamsNotificationTitleError = propertyWithDefault(
            properties, "teams.notification.title.error", "Box 送受信エラー"
        );

        browserCdpUrl = properties.getProperty("browser.cdp.url", "").trim();
        if (browserCdpUrl.isEmpty()) {
            throw new IllegalArgumentException("browser.cdp.url を設定してください（CDP 接続必須）");
        }
        browserSlowMo = Integer.parseInt(properties.getProperty("browser.slow.mo", "100"));
        browserTimeoutMs = Integer.parseInt(properties.getProperty("browser.timeout.ms", "60000"));
        browserTimeoutRetryCount = Integer.parseInt(properties.getProperty("browser.timeout.retry.count", "2"));
        if (browserTimeoutRetryCount < 0) {
            throw new IllegalArgumentException("browser.timeout.retry.count は 0 以上である必要があります");
        }
        uploadCompleteTimeoutMs = Integer.parseInt(properties.getProperty("box.upload.complete.timeout.ms", "120000"));
        boxLoginWaitSeconds = Integer.parseInt(properties.getProperty("box.login.wait.seconds", "300"));
        watchIntervalSeconds = Integer.parseInt(properties.getProperty("watch.interval.seconds", "10"));
        if (watchIntervalSeconds < 1) {
            throw new IllegalArgumentException("watch.interval.seconds は 1 以上である必要があります");
        }

        String folderId = properties.getProperty("box.folder.id", "").trim();
        String folderUrl = properties.getProperty("box.folder.url", "").trim();
        String configuredDomain = optional(properties, "box.enterprise.domain");
        boxEnterpriseDomain = resolveEnterpriseDomain(configuredDomain, folderUrl);
        boxFolderUrl = resolveFolderUrl(folderId, folderUrl, boxEnterpriseDomain);
        localWatchPath = Paths.get(require(properties, "local.watch.path"));
        sendPrefix = propertyWithDefault(properties, "file.send.prefix", "S_");
        receivePrefix = propertyWithDefault(properties, "file.receive.prefix", "R_");
    }

    private static String resolveEnterpriseDomain(String configured, String folderUrl) {
        if (configured != null && !configured.isBlank()) {
            return stripHost(configured);
        }
        String host = hostOf(folderUrl);
        if (host != null && !host.isBlank()) {
            return host;
        }
        throw new IllegalArgumentException("box.enterprise.domain または box.folder.url を設定してください");
    }

    private static String resolveFolderUrl(String folderId, String folderUrl, String domain) {
        if (folderUrl != null && !folderUrl.isEmpty()) {
            return folderUrl;
        }
        if (folderId != null && !folderId.isEmpty()) {
            return "https://" + domain + "/folder/" + folderId;
        }
        throw new IllegalArgumentException("box.folder.id または box.folder.url を設定してください");
    }

    static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(url).getHost();
            return host == null ? null : host.toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripHost(String value) {
        String trimmed = value.trim();
        if (trimmed.contains("://")) {
            String host = hostOf(trimmed);
            if (host != null) {
                return host;
            }
        }
        int slash = trimmed.indexOf('/');
        return (slash < 0 ? trimmed : trimmed.substring(0, slash)).toLowerCase();
    }

    public static Config load(String configPath) throws Exception {
        Properties properties = new Properties();
        InputStream stream = null;

        try {
            if (configPath != null) {
                File file = new File(configPath);
                if (!file.exists()) {
                    throw new IllegalArgumentException("設定ファイルが見つかりません: " + file.getAbsolutePath());
                }
                stream = new FileInputStream(file);
            } else {
                stream = Config.class.getClassLoader().getResourceAsStream("application.properties");
                if (stream == null) {
                    stream = Config.class.getClassLoader().getResourceAsStream("application.properties.example");
                    if (stream != null) {
                        log.warn("注意: application.properties がないため example を使用します。実際の値に編集してください。");
                    }
                }
                if (stream == null) {
                    throw new IllegalArgumentException(
                        "application.properties が見つかりません。"
                            + "src/main/resources/application.properties を作成するか、"
                            + "--config=path/to/application.properties を指定してください。"
                    );
                }
            }

            loadProperties(properties, stream);
            return new Config(properties);
        } finally {
            if (stream != null) {
                stream.close();
            }
        }
    }

    private static void loadProperties(Properties properties, InputStream stream) throws Exception {
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
    }

    private static String propertyWithDefault(Properties properties, String key, String defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static String optional(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private static String require(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("必須設定が未設定です: " + key);
        }
        return value.trim();
    }

    public boolean isSendFile(String fileName) {
        return hasPrefix(fileName, sendPrefix);
    }

    public boolean isReceiveFile(String fileName) {
        return hasPrefix(fileName, receivePrefix);
    }

    public String stripSendPrefix(String fileName) {
        return stripPrefix(fileName, sendPrefix);
    }

    public String stripReceivePrefix(String fileName) {
        return stripPrefix(fileName, receivePrefix);
    }

    private static String stripPrefix(String fileName, String prefix) {
        if (!hasPrefix(fileName, prefix)) {
            return fileName;
        }
        String rest = fileName.substring(prefix.length());
        return rest.isEmpty() ? fileName : rest;
    }

    public boolean matchesExtension(String fileName) {
        if (fileExtensions.isEmpty()) {
            return true;
        }
        String lowerName = fileName.toLowerCase();
        for (String extension : fileExtensions) {
            if (lowerName.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPrefix(String fileName, String prefix) {
        return fileName != null && prefix != null && !prefix.isEmpty() && fileName.startsWith(prefix);
    }

    public String getBoxFolderUrl() {
        return boxFolderUrl;
    }

    public String getBoxEnterpriseDomain() {
        return boxEnterpriseDomain;
    }

    public Path getLocalWatchPath() {
        return localWatchPath;
    }

    public String getSendPrefix() {
        return sendPrefix;
    }

    public String getReceivePrefix() {
        return receivePrefix;
    }

    public int getWatchIntervalSeconds() {
        return watchIntervalSeconds;
    }

    public Set<String> getFileExtensions() {
        return fileExtensions;
    }

    public boolean isTeamsNotificationEnabled() {
        return teamsNotificationEnabled;
    }

    public String getTeamsWebhookUrl() {
        return teamsWebhookUrl;
    }

    public String getTeamsNotificationTitleSendSuccess() {
        return teamsNotificationTitleSendSuccess;
    }

    public String getTeamsNotificationTitleReceiveSuccess() {
        return teamsNotificationTitleReceiveSuccess;
    }

    public String getTeamsNotificationTitleError() {
        return teamsNotificationTitleError;
    }

    public String getBrowserCdpUrl() {
        return browserCdpUrl;
    }

    public int getBrowserSlowMo() {
        return browserSlowMo;
    }

    public int getBrowserTimeoutMs() {
        return browserTimeoutMs;
    }

    public int getBrowserTimeoutRetryCount() {
        return browserTimeoutRetryCount;
    }

    public int getUploadCompleteTimeoutMs() {
        return uploadCompleteTimeoutMs;
    }

    public int getBoxLoginWaitSeconds() {
        return boxLoginWaitSeconds;
    }
}
