package jp.boxsendrecv;

import java.nio.file.Path;

final class PathDisplay {
    private PathDisplay() {
    }

    static String forTeams(Path path) {
        if (path == null) {
            return "";
        }

        String value = path.toAbsolutePath().toString();
        if (value.endsWith("\\") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }

        if (value.startsWith("//")) {
            value = "\\\\" + value.substring(2).replace('/', '\\');
        } else if (value.startsWith("\\") && !value.startsWith("\\\\")) {
            value = "\\" + value;
        }

        if (isUncPath(value)) {
            value = value.replace("\\", "\\\\");
        }

        return value;
    }

    private static boolean isUncPath(String value) {
        if (value.startsWith("\\\\")) {
            return true;
        }
        return value.startsWith("\\") && (value.length() < 3 || value.charAt(2) != ':');
    }
}
