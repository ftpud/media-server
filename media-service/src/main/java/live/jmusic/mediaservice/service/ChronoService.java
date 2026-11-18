package live.jmusic.mediaservice.service;

import org.springframework.stereotype.Service;

/**
 * Rotation clock. Uses a monotonic clock (System.nanoTime), so DST/NTP changes of the wall
 * clock cannot shift the playback position.
 */
@Service
public class ChronoService {
    private static long startNanos = System.nanoTime();

    private static long offset = 0;

    public static synchronized void pushOffset(long num) {
        offset += num;
    }

    public static synchronized Long getTimePointer() {
        return offset + (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** Re-anchor the rotation position (used to sync with the external player). */
    public static synchronized void setPointer(long ms) {
        startNanos = System.nanoTime();
        offset = ms;
    }

    public static synchronized void resetTime() {
        startNanos = System.nanoTime();
        offset = 0;
    }
}
