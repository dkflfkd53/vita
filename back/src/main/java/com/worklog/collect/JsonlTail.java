package com.worklog.collect;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

/**
 * jsonl 을 offset 부터 읽어 줄바꿈으로 끝난 줄만 넘긴다. 쓰는 중인 마지막 줄은 남겨 두고 다음에 이어 읽는다.
 */
public final class JsonlTail {

    private JsonlTail() {
    }

    /** @return 다음에 이어 읽을 위치 */
    public static long read(Path file, long offset, Consumer<String> onLine) throws IOException {
        long consumed = offset;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ch.position(offset);
            InputStream in = new BufferedInputStream(Channels.newInputStream(ch), 1 << 16);
            ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(8192);
            int b;
            while ((b = in.read()) != -1) {
                if (b != '\n') {
                    lineBuf.write(b);
                    continue;
                }
                consumed += lineBuf.size() + 1;
                String line = lineBuf.toString(StandardCharsets.UTF_8);
                lineBuf.reset();
                onLine.accept(line);
            }
        }
        return consumed;
    }
}
