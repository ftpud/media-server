package live.jmusic.shared.util;

import live.jmusic.shared.rest.RestRequestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ConsoleUtilService {


    public void youtubeDl(String url, String fileName, RestRequestService restRequestService) {

        String name = String.valueOf(System.currentTimeMillis());

        String[] cmd = new String[] {"yt-dlp",
                "-f",
                "bestvideo[height<=1080]+bestaudio[ext=m4a]/bestvideo+bestaudio",
                "--merge-output-format",
                "mkv",
                url,
                "-o",
                "/tmp/youtube" + name + ".tmp"};

        runYoutubeDl(cmd, fileName, name, restRequestService);
    }

    public void youtubeDlWithTimeRange(String url, String fileName, String timeRange, RestRequestService restRequestService) {

        String[] cmd = new String[]{"yt-dlp",
                "-f",
                "bestvideo[height<=1080]+bestaudio[ext=m4a]/bestvideo+bestaudio",
                "--merge-output-format",
                "mkv",
                "--download-sections",
                "*".concat(timeRange),
                "--force-keyframes-at-cuts",
                url,
                "-o",
                "/mnt/1tb_m2/user/akb/yt/" + fileName + ".mkv"};


        try {
            log.info("Invoking youtube dl");
            log.info("yt-dlp cmd:" + Arrays.stream(cmd).collect(Collectors.joining(" ")));


            ProcessBuilder processBuilder = new ProcessBuilder(cmd);
            processBuilder.redirectErrorStream(true);

            runBashCommand(processBuilder.start(), true, response -> {
                        restRequestService.sendLiveMessage(fileName.replaceAll("[^a-zA-Z]", ""), "+" + response);
                        System.out.println(response);
                    }
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void runYoutubeDl(String[] cmd, String fileName, String tmpName, RestRequestService restRequestService) {
        log.info("Invoking youtube dl");
        log.info("yt-dlp cmd:" + Arrays.stream(cmd).collect(Collectors.joining(" ")));


        ProcessBuilder processBuilder = new ProcessBuilder(cmd);
        processBuilder.redirectErrorStream(true);


        try {
            runBashCommand(processBuilder.start(), true, response -> {
                        restRequestService.sendLiveMessage(fileName.replaceAll("[^a-zA-Z]", ""), "+" + response);
                        System.out.println(response);
                    }
            );


            Path f = new File("/tmp/youtube" + tmpName + ".tmp.mkv").toPath();
            Path output = Paths.get("/mnt/1tb_m2/user/akb/RequestOnly/youtubedl/", fileName + ".mkv");
            Files.move(f, output, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void runBashCommand(Process process, boolean wait, Consumer<String> lineConsumer) {
        CompletableFuture.runAsync(() -> {
            try {

                // Start the process
                //Process process = Runtime.getRuntime().exec(command);


                // Get the input stream of the process
                InputStream inputStream = process.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

                // Read and process each line from the input stream
                String line;
                while ((line = reader.readLine()) != null) {
                    // Invoke the consumer for each line
                    lineConsumer.accept(line);
                }

                // Wait for the process to finish
                int exitCode = process.waitFor();

                // Print the exit code if needed
                System.out.println("Command exited with code: " + exitCode);

            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        });

        try {


            if (wait) {
                process.waitFor();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
