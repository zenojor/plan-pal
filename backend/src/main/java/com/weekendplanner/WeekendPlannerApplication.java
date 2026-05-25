package com.weekendplanner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@SpringBootApplication
public class WeekendPlannerApplication {

    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(WeekendPlannerApplication.class, args);
    }

    private static void loadDotEnv() {
        Path[] envFiles = {
                Paths.get(".env.local"),
                Paths.get(".env"),
                Paths.get("..", ".env.local"),
                Paths.get("..", ".env")
        };
        for (Path path : envFiles) {
            if (Files.exists(path)) {
                try {
                    List<String> lines = Files.readAllLines(path);
                    for (String line : lines) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue;
                        }
                        int eq = line.indexOf('=');
                        if (eq > 0) {
                            String key = line.substring(0, eq).trim();
                            String value = line.substring(eq + 1).trim();
                            // Remove wrapping quotes if present
                            if (value.startsWith("\"") && value.endsWith("\"")) {
                                value = value.substring(1, value.length() - 1);
                            } else if (value.startsWith("'") && value.endsWith("'")) {
                                value = value.substring(1, value.length() - 1);
                            }
                            if (System.getProperty(key) == null && System.getenv(key) == null) {
                                System.setProperty(key, value);
                            }
                        }
                    }
                } catch (IOException e) {
                    // Ignore and proceed
                }
            }
        }
    }
}
