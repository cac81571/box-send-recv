package jp.boxsendrecv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Stream;

final class LocalWatch {
    private static final Logger log = LoggerFactory.getLogger(LocalWatch.class);
    private static final List<String> SKIP_NAMES = List.of("desktop.ini", "thumbs.db", ".ds_store");
    private static final List<String> SKIP_SUFFIXES = List.of(
        ".tmp", ".temp", ".crdownload", ".part", ".partial", ".download"
    );

    private LocalWatch() {
    }

    static List<Path> listSendFiles(Config config) throws IOException {
        return listRoleFiles(config, config::isSendFile, "送信候補");
    }

    static List<Path> listReceiveFiles(Config config) throws IOException {
        return listRoleFiles(config, config::isReceiveFile, "受信候補");
    }

    private static List<Path> listRoleFiles(
        Config config,
        Predicate<String> nameMatches,
        String label
    ) throws IOException {
        Path root = config.getLocalWatchPath();
        if (!Files.isDirectory(root)) {
            return List.of();
        }

        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                .filter(path -> nameMatches.test(path.getFileName().toString()))
                .filter(path -> config.matchesExtension(path.getFileName().toString()))
                .filter(path -> !isTransient(path))
                .sorted(Comparator.comparing(path -> path.toString().toLowerCase(Locale.ROOT)))
                .forEach(files::add);
        }

        if (!files.isEmpty()) {
            log.info("{}: {} 件", label, files.size());
        }
        return files;
    }

    private static boolean isTransient(Path path) {
        String name = path.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (name.startsWith("~$") || name.startsWith(".")) {
            return true;
        }
        if (SKIP_NAMES.contains(lower)) {
            return true;
        }
        for (String suffix : SKIP_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
