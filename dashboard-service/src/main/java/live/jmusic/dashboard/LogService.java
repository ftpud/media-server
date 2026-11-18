package live.jmusic.dashboard;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Tail and live-follow log files (handles logrotate copytruncate). */
@Service
public class LogService {

    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2);

    /** Last n lines, reading backwards from the end (files can be hundreds of MB). */
    public List<String> tail(File f, int n) throws IOException {
        if (!f.exists()) {
            return Collections.emptyList();
        }
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length();
            int block = 64 * 1024;
            long pos = len;
            byte[] acc = new byte[0];
            int newlines = 0;
            while (pos > 0 && newlines <= n) {
                int sz = (int) Math.min(block, pos);
                pos -= sz;
                byte[] b = new byte[sz];
                r.seek(pos);
                r.readFully(b);
                for (byte x : b) {
                    if (x == '\n') newlines++;
                }
                byte[] merged = new byte[b.length + acc.length];
                System.arraycopy(b, 0, merged, 0, b.length);
                System.arraycopy(acc, 0, merged, b.length, acc.length);
                acc = merged;
                if (acc.length > 8 * 1024 * 1024) break;
            }
            List<String> lines = new ArrayList<>(Arrays.asList(new String(acc, StandardCharsets.UTF_8).split("\n")));
            return lines.subList(Math.max(0, lines.size() - n), lines.size());
        }
    }

    /** Streams new lines as server-sent events until the client goes away. */
    public SseEmitter follow(File f) {
        SseEmitter em = new SseEmitter(0L);
        final long[] offset = {f.length()};
        final ScheduledFuture<?>[] task = new ScheduledFuture<?>[1];
        task[0] = timer.scheduleWithFixedDelay(() -> {
            try {
                long len = f.length();
                if (len < offset[0]) {
                    offset[0] = 0; // truncated by logrotate
                    em.send(SseEmitter.event().name("line").data("--- log rotated ---"));
                }
                if (len > offset[0]) {
                    try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
                        r.seek(offset[0]);
                        byte[] b = new byte[(int) Math.min(len - offset[0], 1024 * 1024)];
                        r.readFully(b);
                        String chunk = new String(b, StandardCharsets.UTF_8);
                        int cut = chunk.lastIndexOf('\n');
                        if (cut >= 0) {
                            offset[0] += chunk.substring(0, cut + 1).getBytes(StandardCharsets.UTF_8).length;
                            for (String line : chunk.substring(0, cut).split("\n")) {
                                em.send(SseEmitter.event().name("line").data(line));
                            }
                        }
                    }
                } else {
                    em.send(SseEmitter.event().comment("keepalive"));
                }
            } catch (Exception e) {
                task[0].cancel(false);
                em.completeWithError(e);
            }
        }, 0, 1, TimeUnit.SECONDS);
        em.onCompletion(() -> task[0].cancel(false));
        em.onTimeout(() -> task[0].cancel(false));
        return em;
    }
}
