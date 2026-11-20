package com.example.elearner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where the Python ingestion script and its interpreter are (see application.yml). */
@ConfigurationProperties(prefix = "app.ingest")
public record IngestProperties(String python, String script, int timeoutSeconds) {
}
