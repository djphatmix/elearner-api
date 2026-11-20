package com.example.elearner.dto;

import java.util.List;

/**
 * Body of POST /api/notes/topics/rename. An admin renamed topics of a subject (or merged one into another
 * topic of the same subject), so everything ingested under each `from` is filed under its `to`.
 */
public record RenameTopicsRequest(String schoolId, String subjectId, List<TopicRename> renames) {
}
