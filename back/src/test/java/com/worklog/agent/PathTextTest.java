package com.worklog.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** macOS·Windows 경로 표기 — OS 를 인자로 넘겨 어느 쪽에서 돌려도 두 경우를 다 본다 */
class PathTextTest {

    private static final String WIN_HOME = "C:\\Users\\marin";
    private static final String MAC_HOME = "/Users/kim";

    @Test
    void tildeOnWindowsIgnoresSeparatorAndCase() {
        assertThat(PathText.tilde("C:\\Users\\marin\\.claude\\CLAUDE.md", WIN_HOME, true)).isEqualTo("~\\.claude\\CLAUDE.md");
        assertThat(PathText.tilde("c:/Users/marin/a.md", WIN_HOME, true)).isEqualTo("~/a.md");
        assertThat(PathText.tilde("/c/Users/marin/a.md", WIN_HOME, true)).isEqualTo("~/a.md"); // git bash
        assertThat(PathText.tilde("C:\\Users\\marina\\a.md", WIN_HOME, true)).isEqualTo("C:\\Users\\marina\\a.md");
        assertThat(PathText.tilde("D:\\work\\a.md", WIN_HOME, true)).isEqualTo("D:\\work\\a.md");
    }

    @Test
    void tildeOnMacIsExact() {
        assertThat(PathText.tilde("/Users/kim/.claude/CLAUDE.md", MAC_HOME, false)).isEqualTo("~/.claude/CLAUDE.md");
        assertThat(PathText.tilde("/Users/kim", MAC_HOME, false)).isEqualTo("~");
        assertThat(PathText.tilde("/Users/kimchi/a.md", MAC_HOME, false)).isEqualTo("/Users/kimchi/a.md");
    }

    @Test
    void untildeKeepsWindowsHomeBackslashes() {
        String old = System.getProperty("user.home");
        System.setProperty("user.home", WIN_HOME);
        try {
            assertThat(PathText.untilde("~\\.claude\\CLAUDE.md").toString().replace('/', '\\'))
                    .isEqualTo("C:\\Users\\marin\\.claude\\CLAUDE.md");
            assertThat(PathText.untilde("D:\\work\\CLAUDE.md").toString().replace('/', '\\')).isEqualTo("D:\\work\\CLAUDE.md");
        } finally {
            System.setProperty("user.home", old);
        }
    }

    @Test
    void keyJoinsSpellingsOfOneFileOnWindowsOnly() {
        assertThat(PathText.key("D:/User/app/README.md", true)).isEqualTo(PathText.key("d:\\User\\app\\readme.md", true));
        assertThat(PathText.key("/c/x/a.java", true)).isEqualTo(PathText.key("C:\\x\\a.java", true));
        assertThat(PathText.key("/Users/kim/A.md", false)).isEqualTo("/Users/kim/A.md");
    }

    @Test
    void shortPathForMemoryAndScratchpadOnBothOs() {
        assertThat(ObserverCalc.shortPath("~/.claude/projects/-Users-kim-app/memory/build.md")).isEqualTo("메모리/build.md");
        assertThat(ObserverCalc.shortPath("~\\.claude\\projects\\C--work-app\\memory\\build.md")).isEqualTo("메모리/build.md");
        assertThat(ObserverCalc.shortPath("/private/tmp/claude-501/-Users-kim-app/1a2b/scratchpad/x.py")).isEqualTo("scratchpad/x.py");
        assertThat(ObserverCalc.shortPath("C:\\Users\\marin\\AppData\\Local\\Temp\\claude\\C--work-app\\1a2b\\scratchpad\\x.py"))
                .isEqualTo("scratchpad/x.py");
        assertThat(ObserverCalc.shortPath("D:\\work\\app\\src\\A.java")).isEqualTo("D:\\work\\app\\src\\A.java");
    }

    @Test
    void signatureUnderstandsWindowsCommands() {
        assertThat(ObserverCalc.signature("\"C:/Users/marin/AppData/Local/Programs/Python/Python312/python.exe\" -c \"print(1)\""))
                .isEqualTo("python");
        assertThat(ObserverCalc.signature("/c/Users/marin/AppData/Local/Programs/Python/Python312/python.exe - <<'EOF'")).isEqualTo("python");
        assertThat(ObserverCalc.signature("& \"C:\\Program Files\\Git\\bin\\git.exe\" status")).isEqualTo("git status");
        assertThat(ObserverCalc.signature(".\\gradlew.bat bootJar -q")).isEqualTo("./gradlew bootJar");
        assertThat(ObserverCalc.signature("npm.cmd run build")).isEqualTo("npm run");
        assertThat(ObserverCalc.signature("/usr/bin/git log --oneline")).isEqualTo("git log");
        assertThat(ObserverCalc.signature("./scripts/run.sh")).isEqualTo("./scripts/run.sh");
    }
}
