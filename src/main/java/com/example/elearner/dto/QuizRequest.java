package com.example.elearner.dto;

import java.util.List;

/**
 * Body of POST /api/quizzes/generate. The questions are written from content uploaded for exactly this
 * grade (every class of it), subject and topic in this school; no other grade is used.
 * `count` is optional (1 to 20, default 5). The questions are written to cover different parts of the topic. `avoid` lists questions that must not be repeated, such as
 * ones already shown or saved (at most the last 50 are used).
 */
public record QuizRequest(String schoolId, String gradeId, String subjectId, String topic, Integer count, List<String> avoid) {
}
