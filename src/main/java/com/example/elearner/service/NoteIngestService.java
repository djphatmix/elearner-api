package com.example.elearner.service;

import com.example.elearner.config.IngestProperties;
import com.example.elearner.dto.TopicRename;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Hands teacher notes to elearner-ai/ingest_notes.py, which embeds them with OpenAI and keeps them in a
 * local ChromaDB. Works like log-analyzer's scan controllers: run the Python script with
 * ProcessBuilder and read the JSON it prints after a marker line.
 *
 * Every value that reaches the command line is validated first, and the process is started with an
 * argument list (no shell), so a title like "; rm -rf /" is just text.
 */
@Service
public class NoteIngestService {

    private static final Logger log = LoggerFactory.getLogger(NoteIngestService.class);
    private static final String MARKER = "---JSON_OUTPUT---";
    private static final Set<String> EXTENSIONS = Set.of("txt", "pdf", "doc", "docx");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final int MAX_TITLE = 120;
    private static final int MAX_BODY = 5000;
    private static final int MAX_LABEL = 120;
    private static final int MAX_QUESTION = 1000;
    private static final int MAX_GRADE_LEVEL = 100;
    private static final int MAX_TOPIC = 80;
    private static final int MAX_TOPIC_RENAMES = 100;
    private static final int MAX_QUIZ_QUESTIONS = 20;
    private static final int DEFAULT_QUIZ_QUESTIONS = 5;
    private static final int MAX_QUIZ_AVOID = 50;
    private static final int MAX_QUIZ_AVOID_LENGTH = 300;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final IngestProperties properties;
    /** ChromaDB's local store is not meant for several writing processes at once, so runs are serialised. */
    private final ReentrantLock lock = new ReentrantLock();

    public NoteIngestService(IngestProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> ingest(String schoolId, String classId, String classLabel,
                                      String gradeId, String gradeName, Integer gradeLevel,
                                      String subjectId, String subjectName, String topic, String noteId,
                                      String title, String authorName, String body, MultipartFile file) {
        requireId("schoolId", schoolId);
        requireId("classId", classId);
        requireId("gradeId", gradeId);
        int level = requireLevel(gradeLevel);
        requireId("subjectId", subjectId);
        String cleanTopic = cleanTopic(topic);
        requireId("noteId", noteId);
        String cleanTitle = clean(title, MAX_TITLE);
        if (cleanTitle.isBlank()) {
            throw bad("Title is required");
        }
        String text = body == null ? "" : body.strip();
        if (text.length() > MAX_BODY) {
            throw bad("Notes can be up to " + MAX_BODY + " characters");
        }
        boolean hasFile = file != null && !file.isEmpty();
        if (text.isEmpty() && !hasFile) {
            throw bad("Send the note text, a file, or both");
        }
        String extension = hasFile ? extensionOf(file) : "";

        Path dir = null;
        try {
            dir = Files.createTempDirectory("elearner-ingest-");
            List<String> args = new ArrayList<>(List.of(
                    "ingest",
                    "--school=" + schoolId,
                    "--class-id=" + classId,
                    "--class-label=" + clean(classLabel, MAX_LABEL),
                    "--grade-id=" + gradeId,
                    "--grade-name=" + clean(gradeName, MAX_LABEL),
                    "--grade-level=" + level,
                    "--subject-id=" + subjectId,
                    "--subject-name=" + clean(subjectName, MAX_LABEL),
                    "--topic=" + cleanTopic,
                    "--note-id=" + noteId,
                    "--title=" + cleanTitle,
                    "--author=" + clean(authorName, MAX_LABEL)));
            if (!text.isEmpty()) {
                Path bodyFile = dir.resolve("body.txt");
                Files.writeString(bodyFile, text, StandardCharsets.UTF_8);
                args.add("--body-file=" + bodyFile);
            }
            if (hasFile) {
                // The stored name is generated here; the visitor's file name only becomes metadata.
                Path upload = dir.resolve("upload." + extension);
                file.transferTo(upload);
                args.add("--file=" + upload);
                args.add("--filename=" + clean(displayName(file), 200));
            }
            return run(args);
        } catch (IOException e) {
            log.error("Could not stage the upload", e);
            throw new IngestException(HttpStatus.INTERNAL_SERVER_ERROR, "The upload could not be processed");
        } finally {
            deleteQuietly(dir);
        }
    }

    public Map<String, Object> delete(String schoolId, String noteId) {
        requireId("schoolId", schoolId);
        requireId("noteId", noteId);
        return run(List.of("delete", "--school=" + schoolId, "--note-id=" + noteId));
    }

    /**
     * Re-files what was ingested under a subject's topics under new topic names (an admin renamed topics, or
     * merged one into another). All changes are worked out from the topics as they are now, so a swap or a chain
     * does what was asked. Only stored metadata changes, so it is quick and costs nothing at OpenAI.
     */
    public Map<String, Object> renameTopics(String schoolId, String subjectId, List<TopicRename> renames) {
        requireId("schoolId", schoolId);
        requireId("subjectId", subjectId);
        if (renames == null || renames.isEmpty() || renames.size() > MAX_TOPIC_RENAMES) {
            throw bad("Send between 1 and " + MAX_TOPIC_RENAMES + " topic changes");
        }
        List<Map<String, String>> cleaned = new ArrayList<>();
        for (TopicRename rename : renames) {
            if (rename == null) {
                throw bad("Each topic change needs a from and a to");
            }
            cleaned.add(Map.of("from", cleanTopic(rename.from()), "to", cleanTopic(rename.to())));
        }

        Path dir = null;
        try {
            dir = Files.createTempDirectory("elearner-topics-");
            // A file, not arguments: up to a hundred changes are too many for a command line.
            Path file = dir.resolve("renames.json");
            Files.writeString(file, JSON.writeValueAsString(cleaned), StandardCharsets.UTF_8);
            return run(List.of("rename-topics", "--school=" + schoolId, "--subject-id=" + subjectId, "--renames-file=" + file));
        } catch (IOException e) {
            log.error("Could not stage the topic changes", e);
            throw new IngestException(HttpStatus.INTERNAL_SERVER_ERROR, "The topic changes could not be processed");
        } finally {
            deleteQuietly(dir);
        }
    }

    /**
     * The chunks closest to a question that the asker may see: the same school, and content written
     * for the asker's grade level or any level below it.
     */
    public Map<String, Object> search(String schoolId, Integer gradeLevel, String subjectId, String topic,
                                      String question, Integer limit) {
        requireId("schoolId", schoolId);
        int level = requireLevel(gradeLevel);
        String q = clean(question, MAX_QUESTION);
        if (q.isBlank()) {
            throw bad("Question is required");
        }
        int n = limit == null ? 5 : Math.max(1, Math.min(limit, 20));
        List<String> args = new ArrayList<>(List.of(
                "search", "--school=" + schoolId, "--grade-level=" + level, "--question=" + q, "--limit=" + n));
        // Both filters are optional: leave them out to search every subject and topic the asker may see.
        if (subjectId != null && !subjectId.isBlank()) {
            requireId("subjectId", subjectId);
            args.add("--subject-id=" + subjectId);
        }
        if (topic != null && !topic.isBlank()) {
            args.add("--topic=" + cleanTopic(topic));
        }
        return run(args);
    }

    /**
     * Draft multiple choice questions for one grade, subject and topic, written by the chat model from the
     * content uploaded for exactly that grade. Nothing is stored. `avoid` holds questions not to repeat.
     */
    public Map<String, Object> generateQuiz(String schoolId, String gradeId, String subjectId, String topic,
                                            Integer count, List<String> avoid) {
        requireId("schoolId", schoolId);
        requireId("gradeId", gradeId);
        requireId("subjectId", subjectId);
        String cleanTopic = cleanTopic(topic);
        int n = count == null ? DEFAULT_QUIZ_QUESTIONS : count;
        if (n < 1 || n > MAX_QUIZ_QUESTIONS) {
            throw bad("count must be a whole number from 1 to " + MAX_QUIZ_QUESTIONS);
        }
        List<String> earlier = new ArrayList<>();
        if (avoid != null) {
            for (String question : avoid) {
                String q = clean(question, MAX_QUIZ_AVOID_LENGTH);
                if (!q.isBlank()) {
                    earlier.add(q);
                }
            }
            // Only the most recent ones matter, and the file stays small.
            if (earlier.size() > MAX_QUIZ_AVOID) {
                earlier = new ArrayList<>(earlier.subList(earlier.size() - MAX_QUIZ_AVOID, earlier.size()));
            }
        }

        Path dir = null;
        try {
            dir = Files.createTempDirectory("elearner-quiz-");
            List<String> args = new ArrayList<>(List.of(
                    "quiz",
                    "--school=" + schoolId,
                    "--grade-id=" + gradeId,
                    "--subject-id=" + subjectId,
                    "--topic=" + cleanTopic,
                    "--count=" + n));
            if (!earlier.isEmpty()) {
                // A file, not arguments: fifty questions are too much for a command line.
                Path avoidFile = dir.resolve("avoid.json");
                Files.writeString(avoidFile, JSON.writeValueAsString(earlier), StandardCharsets.UTF_8);
                args.add("--avoid-file=" + avoidFile);
            }
            return run(args);
        } catch (IOException e) {
            log.error("Could not stage the quiz request", e);
            throw new IngestException(HttpStatus.INTERNAL_SERVER_ERROR, "The quiz request could not be processed");
        } finally {
            deleteQuietly(dir);
        }
    }

    // ------------------------------------------------------------------ running the script

    private Map<String, Object> run(List<String> scriptArgs) {
        Path script = Paths.get(properties.script()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(script)) {
            throw new IngestException(HttpStatus.SERVICE_UNAVAILABLE, "The ingestion script was not found");
        }
        String python = resolvePython(properties.python());

        List<String> command = new ArrayList<>();
        command.add(python);
        command.add(script.toString());
        command.addAll(scriptArgs);

        Path output = null;
        lock.lock();
        try {
            output = Files.createTempFile("elearner-ingest-out-", ".txt");
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(script.getParent().toFile());
            pb.redirectErrorStream(true);
            // Output goes to a file, not a pipe, so a chatty script can never fill a buffer and hang the wait.
            pb.redirectOutput(output.toFile());

            Process process = pb.start();
            if (!process.waitFor(properties.timeoutSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IngestException(HttpStatus.GATEWAY_TIMEOUT,
                        "Ingestion timed out after " + properties.timeoutSeconds() + " seconds");
            }
            String text = Files.readString(output, StandardCharsets.UTF_8);
            return parse(text, process.exitValue());
        } catch (IOException e) {
            log.error("Could not start the ingestion script", e);
            throw new IngestException(HttpStatus.SERVICE_UNAVAILABLE, "The ingestion script could not be started");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestException(HttpStatus.INTERNAL_SERVER_ERROR, "Ingestion was interrupted");
        } finally {
            lock.unlock();
            deleteQuietly(output);
        }
    }

    private Map<String, Object> parse(String text, int exitCode) {
        int marker = text.lastIndexOf(MARKER);
        if (marker < 0) {
            // Keep the details in the server log only; the script's own output stays off the wire.
            log.error("Ingestion script exited with {} and no result. Output tail: {}", exitCode, tail(text));
            throw new IngestException(HttpStatus.INTERNAL_SERVER_ERROR, "The ingestion script failed to run");
        }
        String json = text.substring(marker + MARKER.length()).strip();
        Map<String, Object> result;
        try {
            result = JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            log.error("Unreadable result from the ingestion script: {}", tail(json));
            throw new IngestException(HttpStatus.INTERNAL_SERVER_ERROR, "The ingestion script returned an unreadable result");
        }
        if (exitCode == 0 && !"error".equals(result.get("status"))) {
            return result;
        }
        // The browser gets the short message; the script's own output (which names the real cause) stays in the server log.
        log.warn("Ingestion refused ({}): {}. Script output tail: {}", result.get("code"), result.get("message"), tail(text));
        throw new IngestException(statusFor(String.valueOf(result.get("code"))),
                String.valueOf(result.getOrDefault("message", "Ingestion failed")));
    }

    private static HttpStatus statusFor(String code) {
        return switch (code) {
            case "bad_input" -> HttpStatus.BAD_REQUEST;
            case "unsupported" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "no_material" -> HttpStatus.NOT_FOUND;
            case "config" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "upstream" -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static String resolvePython(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IngestException(HttpStatus.SERVICE_UNAVAILABLE, "No Python interpreter is configured");
        }
        // A bare name such as python3 is looked up on the PATH; a path must exist.
        if (!configured.contains("/") && !configured.contains("\\")) {
            return configured;
        }
        Path path = Paths.get(configured).toAbsolutePath().normalize();
        if (!Files.isExecutable(path)) {
            throw new IngestException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The Python environment is not set up. Run: python3 -m venv elearner-ai/.venv && elearner-ai/.venv/bin/pip install -r elearner-ai/requirements.txt");
        }
        return path.toString();
    }

    // ------------------------------------------------------------------ validation helpers

    private static void requireId(String name, String value) {
        if (value == null || !ID.matcher(value).matches()) {
            throw bad(name + " must be 1 to 64 letters, digits, dashes or underscores");
        }
    }

    /** A topic such as "Comprehension training": control characters removed, spaces collapsed, 1 to 80 characters. */
    private static String cleanTopic(String value) {
        String topic = value == null ? "" : value.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        if (topic.isEmpty()) {
            throw bad("Topic is required");
        }
        if (topic.length() > MAX_TOPIC) {
            throw bad("Topic must be " + MAX_TOPIC + " characters or fewer");
        }
        return topic;
    }

    /** A grade level is the grade's place in the school's grade order, from 1 (lowest). */
    private static int requireLevel(Integer value) {
        if (value == null || value < 1 || value > MAX_GRADE_LEVEL) {
            throw bad("gradeLevel must be a whole number from 1 to " + MAX_GRADE_LEVEL);
        }
        return value;
    }

    /** Drops control characters (they cannot be passed on a command line) and trims to a length. */
    private static String clean(String value, int max) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("\\p{Cntrl}", " ").strip();
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    private static String extensionOf(MultipartFile file) {
        String name = displayName(file);
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(extension)) {
            throw new IngestException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only txt, pdf, doc and docx files can be ingested");
        }
        return extension;
    }

    /** The uploaded file's own name without any folder part. */
    private static String displayName(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        return name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    }

    private static IngestException bad(String message) {
        return new IngestException(HttpStatus.BAD_REQUEST, message);
    }

    private static String tail(String text) {
        return text.length() <= 2000 ? text : text.substring(text.length() - 2000);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // A leftover temp file is not worth failing the request for.
        }
    }
}
