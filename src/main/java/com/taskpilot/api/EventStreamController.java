package com.taskpilot.api;

import com.taskpilot.core.TaskEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * SSE controller — streams live task events to the UI.
 * Each task step is pushed as a Server-Sent Event.
 */
@RestController
@RequestMapping("/api/tasks")
@CrossOrigin(origins = "*")
public class EventStreamController {

    private static final Logger log = LoggerFactory.getLogger(EventStreamController.class);

    // taskId → list of active SSE emitters
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /** GET /api/tasks/{id}/events — SSE stream for a task. */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@PathVariable String id) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout
        emitters.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(id, emitter));
        emitter.onTimeout(() -> removeEmitter(id, emitter));
        emitter.onError(e -> removeEmitter(id, emitter));

        log.debug("SSE client connected for task {}", id);
        return emitter;
    }

    /** Listen for TaskEvents from the agent loop and push to SSE clients. */
    @EventListener
    public void onTaskEvent(TaskEvent event) {
        List<SseEmitter> taskEmitters = emitters.get(event.getTaskId());
        if (taskEmitters == null || taskEmitters.isEmpty()) return;

        String data = """
            {"type":"%s","message":"%s","taskId":"%s"}
            """.formatted(
                event.getType(),
                event.getMessage().replace("\"", "'"),
                event.getTaskId()
            ).strip();

        List<SseEmitter> dead = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : taskEmitters) {
            try {
                emitter.send(SseEmitter.event().data(data).name("step"));
            } catch (IOException e) {
                log.debug("SSE emitter dead for task {}", event.getTaskId());
                dead.add(emitter);
            }
        }
        taskEmitters.removeAll(dead);
    }

    private void removeEmitter(String taskId, SseEmitter emitter) {
        List<SseEmitter> taskEmitters = emitters.get(taskId);
        if (taskEmitters != null) taskEmitters.remove(emitter);
    }
}
