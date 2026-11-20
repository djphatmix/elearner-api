package com.example.elearner.controller;

import com.example.elearner.dto.QuizRequest;
import com.example.elearner.service.NoteIngestService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Draft quiz questions written by an AI model from the knowledge base. Nothing is stored here: the
 * teacher reviews the draft in the app and the app saves only what was approved.
 *
 * NOTE: like /api/notes there is no authentication yet, and the school comes from the request. Anyone who
 * can reach this port can spend the OpenAI account's credits and read questions drawn from any school's
 * notes. Put real authentication in front of it (the school, and the grades and subjects the caller
 * teaches, must come from the signed-in user, not the request) before exposing it beyond a trusted machine.
 */
@RestController
@RequestMapping("/api/quizzes")
public class QuizController {

    private final NoteIngestService service;

    public QuizController(NoteIngestService service) {
        this.service = service;
    }

    /** Up to 5 multiple choice questions: {status, questions:[{text, options[4], correctIndex, explanation}], sources}. */
    @PostMapping(path = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> generate(@RequestBody QuizRequest request) {
        return service.generateQuiz(request.schoolId(), request.gradeId(), request.subjectId(), request.topic(), request.count(), request.avoid());
    }
}
