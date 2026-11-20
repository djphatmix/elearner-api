package com.example.elearner.dto;

/**
 * Body of POST /api/notes/search. `gradeLevel` is the asker's grade level (the grade's place in the
 * school's grade order, from 1); only content written for that grade or a lower one is returned.
 * `subjectId` and `topic` are optional filters (the topic ignores case and spacing).
 * `limit` is optional (default 5, at most 20).
 */
public record SearchRequest(String schoolId, Integer gradeLevel, String subjectId, String topic, String question, Integer limit) {
}
