package com.shapira.examples.streams.stockstats;

import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Load configuration from a file. This is mainly intended for connection info, so I can switch between clusters without recompile
 * But you can put other client configuration here, but we may override it...
 */
public class LoadConfigs {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(LoadConfigs.class);

    // default to cloud, duh
    private static final String DEFAULT_CONFIG_File =
            System.getProperty("user.home") + File.separator + ".ccloud" + File.separator + "config";

    static Properties loadConfig() throws IOException {
        return loadConfig(DEFAULT_CONFIG_File);
    }

    static Properties loadConfig(Properties existing) throws IOException {
        return loadConfig(existing, DEFAULT_CONFIG_File);
    }

    static Properties loadConfig(String configFile) throws IOException {
        if (!Files.exists(Paths.get(configFile))) {
            throw new RuntimeException(configFile + " does not exist. You need a file with client configuration, " +
                    "either create one or run `ccloud init` if you are a Confluent Cloud user");
        }
        log.info("Loading configs from: {}", configFile);
        final Properties cfg = new Properties();
        try (InputStream inputStream = new FileInputStream(configFile)) {
            cfg.load(inputStream);
        }

        return cfg;
    }

    static Properties loadConfig(Properties existing, String configFile) throws IOException {
        if (!Files.exists(Paths.get(configFile))) {
            throw new RuntimeException(configFile + " does not exist. You need a file with client configuration, " +
                    "either create one or run `ccloud init` if you are a Confluent Cloud user");
        }
        log.info("Loading configs from: {}", configFile);
        try (InputStream inputStream = new FileInputStream(configFile)) {
            existing.load(inputStream);
        }

        return existing;
    }
}
