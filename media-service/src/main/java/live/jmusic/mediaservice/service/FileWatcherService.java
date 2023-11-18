package live.jmusic.mediaservice.service;

import live.jmusic.shared.model.MediaItem;
import live.jmusic.shared.rest.RestRequestService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

@Slf4j
@Service
public class FileWatcherService {

    @Value("${media.library.path}")
    private String mediaLibraryPath;

    @Autowired
    RestRequestService restRequestService;

    @Autowired
    MediaDbService mediaDbService;

    public static void isFileReady(File entry, BiConsumer<File, Integer> inProgress) {
        try {
            int i = 0;
            Long lastAccess = 0L;
            while (lastAccess != Files.readAttributes(entry.toPath(), BasicFileAttributes.class).lastModifiedTime().toMillis()) {
                lastAccess = Files.readAttributes(entry.toPath(), BasicFileAttributes.class).lastModifiedTime().toMillis();
                Thread.sleep(5000);
                inProgress.accept(entry, i);
                i++;
            }

        } catch (Exception e) {
            log.info(e.toString());
        }
    }

    private void waitUntilIsReadable(File file, BiConsumer<File, Integer> inProgress) throws InterruptedException {
        boolean isReadable = false;
        int loopsNumber = 1;
        while (!isReadable && Files.exists(file.toPath())) {
            try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                log.trace("InputStream readable. Available: {}. File: '{}'",
                        in.available(), file.getAbsolutePath());
                isReadable = true;
            } catch (Exception e) {
                log.trace("InputStream is not readable yet. File: '{}'", file.getAbsolutePath());
                loopsNumber++;
                inProgress.accept(file, loopsNumber);
                TimeUnit.MILLISECONDS.sleep(1000);
            }
        }
    }

    public void run() throws IOException {
        Path dir = Paths.get(mediaLibraryPath);

        FileAlterationObserver observer = new FileAlterationObserver(dir.toFile());

        log.info("Start ACTIVITY, Monitoring " + dir);
        observer.addListener(new FileAlterationListenerAdaptor() {
            @Override
            public void onFileCreate(File file) {
                log.info("File Created:" + file.getName() + ": YOUR ACTION");
                CompletableFuture<Boolean> uploadStart = CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                restRequestService.sendLiveMessage("Upload started: " + file.getName());
                                isFileReady(file,
                                        (s, t) -> restRequestService.sendLiveMessage(
                                                s.getName(), String.format("Uploading [%s] %s", s.getName(), t)));

                                if (file.exists()) {
                                    restRequestService.sendLiveMessage("Upload completed " + file.getName());
                                    MediaItem item = mediaDbService.processFile(file, true);
                                    mediaDbService.processItemVolume(item);
                                } else {
                                    restRequestService.sendLiveMessage("Upload aborted " + file.getName());
                                }
                                return true;
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                );

            }

        });


        FileAlterationMonitor monitor = new FileAlterationMonitor(500, observer);
        try {
            monitor.start();
        } catch (Exception e) {
            log.error("UNABLE TO MONITOR SERVER" + e.getMessage());
            e.printStackTrace();

        }
    }
}
