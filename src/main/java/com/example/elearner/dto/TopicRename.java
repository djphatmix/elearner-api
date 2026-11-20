package com.example.elearner.dto;

/** One topic change: what was ingested under `from` is filed under `to`. Both ignore case and spacing when matching. */
public record TopicRename(String from, String to) {
}
