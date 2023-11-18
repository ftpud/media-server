package com.example.msrv2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;

@RestController
@RequestMapping("/")
class ServiceController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceController.class);
    private static final String DEPLOY_PATH = "/home/ftpud/media-server/deploy/";
    private static final String LOGS_PATH = "/home/ftpud/media-server/app/logs/";

    private final List<String> validServices = Arrays.asList(
            "balance-service.jar",
            "publisher-service.jar",
            "live-service.jar",
            "chat-service.jar",
            "media-service.jar",
            "stream-service.jar",
            "ff_stream",
            "dashboard-service.jar"
    );

    private final Map<String, List<String>> serviceLogMap = new HashMap<>();

    public ServiceController() {
        // Initialize the serviceLogMap
        serviceLogMap.put("balance-service.jar", Arrays.asList("balance-service.log", "log-balancer.log"));
        serviceLogMap.put("publisher-service.jar", Arrays.asList("publisher-service.log", "log-sub-ffmpeg.log"));
        serviceLogMap.put("live-service.jar", Collections.singletonList("live-service.log"));
        serviceLogMap.put("chat-service.jar", Collections.singletonList("chat-service.log"));
        serviceLogMap.put("media-service.jar", Collections.singletonList("media-service.log"));
        serviceLogMap.put("stream-service.jar", Collections.singletonList("stream-service.log"));
        serviceLogMap.put("ff_stream", Collections.singletonList("---log-stream-ffmpeg.log"));
        serviceLogMap.put("dashboard-service.jar", Arrays.asList("dashboard-service.log"));
    }

    @GetMapping("/status")
    public List<StatusResponse> getStatus() {
        LOGGER.info("GET /status");
        List<StatusResponse> responses = new ArrayList<>();
        for (String service : getServices()) {
            StatusResponse response = new StatusResponse();
            response.setServiceName(service);
            response.setStatus(isServiceRunning(service) ? "up" : "down");
            response.setDeployable(isDeployable(service));
            response.setLogs(getLogsForService(service));
            responses.add(response);
        }
        return responses;
    }

    @GetMapping("/start/{service}")
    public String startService(@PathVariable String service) {
        LOGGER.info("GET /start/{}", service);
        validateService(service);
        // Remove '.jar' from service for logs path
        String logsService = service.replace(".jar", "");
        String command = "nohup java -jar " + service + " --spring.config.location=classpath:/application.properties,config.properties > " + LOGS_PATH + logsService + ".log 2>&1 &";
        executeCommand(command);
        // If restarting 'publisher-service.jar', also restart 'balance-service.jar'
        if (service.equals("publisher-service.jar")) {
            stopService("balance-service.jar");
        }
        return "Service started: " + service;
    }

    @GetMapping("/restart/{service}")
    public String restartService(@PathVariable String service) {
        LOGGER.info("GET /restart/{}", service);
        validateService(service);
        if (isServiceRunning(service)) {
            executeCommand("kill -9 " + getServicePid(service));
        }
        return startService(service);
    }

    @GetMapping("/stop/{service}")
    public String stopService(@PathVariable String service) {
        LOGGER.info("GET /stop/{}", service);
        validateService(service);
        if (isServiceRunning(service)) {
            executeCommand("kill -9 " + getServicePid(service));
            return "Service stopped: " + service;
        } else {
            return "Service is not running: " + service;
        }
    }

    @GetMapping("/log/{logName}")
    public List<String> getServiceLog(@PathVariable String logName) {
        LOGGER.info("GET /log/{}", logName);
        validateLog(logName);
        // Replace "up/" with "---log-stream-ffmpeg.log" in the logName
        logName = logName.replace("---", "../");
        String logFilePath = LOGS_PATH + logName;
        return getLastLines(logFilePath, 100);
    }

    @GetMapping("/deploy/{service}")
    public String deployService(@PathVariable String service) {
        LOGGER.info("GET /deploy/{}", service);
        validateService(service);
        File source = new File(DEPLOY_PATH + service);
        File destination = new File(service);
        // Remove destination file if it exists
        if (destination.exists()) {
            destination.delete();
        }
        if (source.exists()) {
            source.renameTo(destination);
            restartService(service);
            return "Service deployed: " + service;
        } else {
            return "Service not found in deploy path: " + service;
        }
    }

    private List<String> getLogsForService(String service) {
        List<String> logs = new ArrayList<>();
        List<String> logFiles = serviceLogMap.get(service);
        if (logFiles != null) {
            logs.addAll(logFiles);
        }
        return logs;
    }

    private void validateService(String service) {
        if (!validServices.contains(service)) {
            LOGGER.error("Invalid service: {}", service);
            throw new IllegalArgumentException("Invalid service: " + service);
        }
    }

    private void validateLog(String logName) {
        if (serviceLogMap.values().stream().noneMatch(logs -> logs.contains(logName))) {
            LOGGER.error("Invalid log name: {}", logName);
            throw new IllegalArgumentException("Invalid log name: " + logName);
        }
    }

    private List<String> getLastLines(String filePath, int lines) {
        List<String> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Runtime.getRuntime().exec(new String[]{"bash", "-c", "tail -n " + lines + " " + filePath}).getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.add(line);
            }
        } catch (IOException e) {
            LOGGER.error("Error reading log file: {}", e.getMessage());
            e.printStackTrace();
        }
        return result;
    }

    private boolean isServiceRunning(String service) {
        String command = "pgrep -f " + service;
        return !executeCommand(command).trim().isEmpty();
    }

    private boolean isDeployable(String service) {
        return new File(DEPLOY_PATH + service).exists();
    }

    private List<String> getServices() {
        return validServices;
    }

    private String getServicePid(String service) {
        String command = "pgrep -f " + service;
        return executeCommand(command).trim();
    }

    private String executeCommand(String command) {
        StringBuilder output = new StringBuilder();
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"bash", "-c", command});
            process.waitFor();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Error executing command: {}", e.getMessage());
            e.printStackTrace();
        }
        return output.toString();
    }
}