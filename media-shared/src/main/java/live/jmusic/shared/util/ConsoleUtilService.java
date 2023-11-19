package live.jmusic.shared.util;

import live.jmusic.shared.rest.RestRequestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Slf4j
@Service
public class ConsoleUtilService {

    public void youtubeDl(String url, String fileName, RestRequestService restRequestService) {
        log.info("Invoking youtube dl");

        ProcessBuilder processBuilder = new ProcessBuilder(
                "yt-dlp",
                "-f",
                "bestvideo[height<=1080]+bestaudio[ext=m4a]/bestvideo+bestaudio",
                "--merge-output-format",
                "mkv",
                url,
                "-o",
                "/tmp/youtube.tmp");
        processBuilder.redirectErrorStream(true);


        try {
            runBashCommand(processBuilder.start(), response -> {
                        restRequestService.sendLiveMessage(fileName.replaceAll("[^a-zA-Z]", ""), "+" + response);
                        System.out.println(response);
                    }
            );

            Path f = new File("/tmp/youtube.tmp.mkv").toPath();
            Path output = Paths.get("/mnt/1tb_3/content/RequestOnly/youtubedl/", fileName + ".mkv");
            Files.move(f, output, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void runBashCommand(Process process, Consumer<String> lineConsumer) {
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
    }

}
