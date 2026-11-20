package com.example.elearner.controller;

import com.example.elearner.dto.RenameTopicsRequest;
import com.example.elearner.dto.SearchRequest;
import com.example.elearner.service.NoteIngestService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Teacher notes for the shared knowledge base.
 *
 * NOTE: there is no authentication yet, and the school comes from the request. Anyone who can reach
 * this port can ingest, search and delete for any school. Put real authentication in front of it (the
 * school must come from the signed-in user, not the request) before exposing it beyond a trusted machine.
 */
@RestController
@RequestMapping("/api/notes")
public class NoteIngestController {

    private final NoteIngestService service;

    public NoteIngestController(NoteIngestService service) {
        this.service = service;
    }

    /** Adds or replaces a note's content. Send the text, a txt/pdf/doc/docx file, or both. */
    @PostMapping(path = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> ingest(
            @RequestParam String schoolId,
            @RequestParam String classId,
            @RequestParam(required = false) String classLabel,
            @RequestParam String gradeId,
            @RequestParam(required = false) String gradeName,
            @RequestParam Integer gradeLevel,
            @RequestParam String subjectId,
            @RequestParam(required = false) String subjectName,
            @RequestParam String topic,
            @RequestParam String noteId,
            @RequestParam String title,
            @RequestParam(required = false) String authorName,
            @RequestParam(required = false) String body,
            @RequestPart(required = false) MultipartFile file) {
        return service.ingest(schoolId, classId, classLabel, gradeId, gradeName, gradeLevel, subjectId, subjectName, topic, noteId, title, authorName, body, file);
    }

    /** Removes everything ingested for one note. */
    @DeleteMapping("/{noteId}")
    public Map<String, Object> delete(@PathVariable String noteId, @RequestParam String schoolId) {
        return service.delete(schoolId, noteId);
    }

    /** Re-files what was ingested under a subject's topics under new names (an admin renamed or merged topics). */
    @PostMapping(path = "/topics/rename", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> renameTopics(@RequestBody RenameTopicsRequest request) {
        return service.renameTopics(request.schoolId(), request.subjectId(), request.renames());
    }

    /** The chunks closest to a question that the asker may see: same school, their grade level or lower. */
    @PostMapping(path = "/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> search(@RequestBody SearchRequest request) {
        return service.search(request.schoolId(), request.gradeLevel(), request.subjectId(), request.topic(), request.question(), request.limit());
    }
}
