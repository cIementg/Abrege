package com.efrei.abregefrr.live;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/live")
public class LiveStreamController {

    private final LiveTranscriptionService transcriptionService;

    public LiveStreamController(LiveTranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return transcriptionService.subscribe();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "listening", transcriptionService.isMicrophoneReady()
        );
    }
}

