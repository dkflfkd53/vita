package com.worklog.agent;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 기록에 남은 경로 글을 다룬다. macOS 는 /, Windows 는 \ 가 기본이지만 도구마다 C:/…, c:\…, git bash 의 /c/… 처럼 섞여 남는다.
 * 그래서 홈 줄이기·같은 파일 묶기·이름 뽑기는 어느 표기든 같은 뜻으로 본다(Windows 는 대소문자도 무시).
 */
final class PathText {

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private PathText() {
    }

    /** 홈 아래면 ~ 로 줄인다 */
    static String tilde(String path) {
        return tilde(path, System.getProperty("user.home"), WINDOWS);
    }

    static String tilde(String path, String home, boolean windows) {
        if (path == null || path.length() < home.length()) {
            return path;
        }
        boolean under = key(path.substring(0, home.length()), windows).equals(key(home, windows))
                && (path.length() == home.length() || isSep(path.charAt(home.length())));
        return under ? "~" + path.substring(home.length()) : path;
    }

    /** tilde 의 반대. replaceFirst 는 치환 글의 \ 를 이스케이프로 먹어 Windows 홈(C:\Users\…)이 깨지므로 문자열로 잇는다 */
    static Path untilde(String path) {
        return Path.of(path.startsWith("~") ? System.getProperty("user.home") + path.substring(1) : path);
    }

    /** 같은 파일을 한 이름으로 묶는 키 */
    static String key(String path) {
        return key(path, WINDOWS);
    }

    static String key(String path, boolean windows) {
        if (path == null || !windows) {
            return path;
        }
        String s = path.replace('/', '\\');
        if (s.length() >= 3 && s.charAt(0) == '\\' && Character.isLetter(s.charAt(1)) && s.charAt(2) == '\\') {
            s = s.charAt(1) + ":" + s.substring(2); // git bash /c/… → c:\…
        }
        return s.toLowerCase(Locale.ROOT);
    }

    /** 경로의 마지막 이름(/ · \ 둘 다) */
    static String baseName(String path) {
        return path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
    }

    static boolean isSep(char c) {
        return c == '/' || c == '\\';
    }
}
