package com.efrei.abregefrr.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class LiveTranscriptionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LiveTranscriptionService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ExecutorService recognitionExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "live-recognition");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService summaryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "live-summary");
        t.setDaemon(true);
        return t;
    });
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    private final AtomicBoolean shouldRun = new AtomicBoolean(true);
    private final AtomicBoolean loopStarted = new AtomicBoolean(false);
    private final AtomicBoolean microphoneReady = new AtomicBoolean(false);
    private TargetDataLine microphoneLine;

    @Value("${vosk.model-path:src/main/resources/model/vosk-model}")
    private String modelPath;

    @Value("${live.sample-rate:16000}")
    private int sampleRate;

    @Value("${live.buffer-size:4096}")
    private int bufferSize;

    @Value("${live.restart-delay-ms:3000}")
    private long restartDelayMs;

    @Value("${live.autostart:true}")
    private boolean autoStart;

    @Value("${ollama.url:http://localhost:11434/api/generate}")
    private String ollamaUrl;

    @Value("${ollama.model:gemma3:4b}")
    private String ollamaModel;

    @Value("${ollama.prompt:Résume très brièvement, en gardant les infos clés et sans définition : %s}")
    private String ollamaPromptTemplate;

    @PostConstruct
    public void boot() {
        if (autoStart) {
            startLoopIfNeeded();
        }
    }

    @PreDestroy
    public void shutdown() {
        shouldRun.set(false);
        closeMicrophone();
        recognitionExecutor.shutdownNow();
        summaryExecutor.shutdownNow();
        emitters.forEach(SseEmitter::complete);
    }

    public SseEmitter subscribe() {
        startLoopIfNeeded();
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            emitters.remove(emitter);
        });
        sendSafe(emitter, new LiveEventPayload("status", microphoneReady.get() ? "listening" : "initialising"));
        return emitter;
    }

    private void startLoopIfNeeded() {
        if (loopStarted.compareAndSet(false, true)) {
            recognitionExecutor.submit(this::runForever);
        }
    }

    public boolean isMicrophoneReady() {
        return microphoneReady.get();
    }

    private void runForever() {
        while (shouldRun.get()) {
            try {
                runLoop();
            } catch (Exception e) {
                LOGGER.error("Live recognition stopped unexpectedly", e);
                broadcast(new LiveEventPayload("error", "Reconnaissance stoppée : " + e.getMessage()));
                sleepQuietly(restartDelayMs);
            }
        }
    }

    private void runLoop() throws Exception {
        microphoneReady.set(false);

        String resolvedModelPath = Paths.get(modelPath).toAbsolutePath().toString();
        LOGGER.info("Chargement du modèle Vosk depuis {}", resolvedModelPath);

        DataLine.Info info = new DataLine.Info(TargetDataLine.class, audioFormat());
        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("Micro non supporté par le format " + info);
        }

        try (Model model = new Model(resolvedModelPath);
             Recognizer recognizer = new Recognizer(model, sampleRate)) {

            microphoneLine = (TargetDataLine) AudioSystem.getLine(info);
            microphoneLine.open(audioFormat());
            microphoneLine.start();
            microphoneReady.set(true);
            broadcast(new LiveEventPayload("status", "listening"));
            LOGGER.info("Micro prêt, écoute en cours...");

            byte[] buffer = new byte[bufferSize];

            while (shouldRun.get()) {
                int bytesRead = microphoneLine.read(buffer, 0, buffer.length);
                if (bytesRead <= 0) {
                    continue;
                }

                if (recognizer.acceptWaveForm(buffer, bytesRead)) {
                    String sentence = extractText(recognizer.getResult());
                    if (!sentence.isBlank()) {
                        broadcast(new LiveEventPayload("transcript-final", sentence));
                        summaryExecutor.submit(() -> streamSummary(sentence));
                    }
                } else {
                    String partial = extractText(recognizer.getPartialResult());
                    if (!partial.isBlank()) {
                        broadcast(new LiveEventPayload("transcript-partial", partial));
                    }
                }
            }
        } finally {
            microphoneReady.set(false);
            broadcast(new LiveEventPayload("status", "stopped"));
            closeMicrophone();
        }
    }

    private AudioFormat audioFormat() {
        return new AudioFormat(sampleRate, 16, 1, true, false);
    }

    private void streamSummary(String text) {
        broadcast(new LiveEventPayload("summary-start", text));
        try {
            URL url = new URL(ollamaUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setReadTimeout(60_000);

            String prompt = String.format(ollamaPromptTemplate, text.replace("\"", "\\\""));
            String body = """
                    {
                      "model": "%s",
                      "prompt": "%s",
                      "stream": false
                    }
                    """.formatted(ollamaModel, prompt);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(body.getBytes());
                os.flush();
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                String summary = extractOllamaResponse(sb.toString())
                        .replace("\\n", " ")
                        .replace("\n", " ")
                        .replace("\r", " ")
                        .trim();
                if (!summary.isEmpty()) {
                    broadcast(new LiveEventPayload("summary-token", summary));
                }
            }

            broadcast(new LiveEventPayload("summary-end", text));

        } catch (IOException e) {
            LOGGER.warn("Erreur Ollama : {}", e.getMessage());
            broadcast(new LiveEventPayload("error", "Ollama indisponible : " + e.getMessage()));
        }
    }

    private String extractOllamaResponse(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode response = node.get("response");
            if (response != null && response.isTextual()) {
                return response.asText();
            }
        } catch (IOException e) {
            LOGGER.debug("Impossible de parser la réponse Ollama : {}", e.getMessage());
        }
        int idx = json.indexOf("\"response\"");
        if (idx == -1) {
            return "";
        }
        String tail = json.substring(idx + 11);
        return tail.replaceAll("^\\s*:\\s*\"", "")
                .replaceAll("\".*", "")
                .trim();
    }

    private void broadcast(LiveEventPayload payload) {
        emitters.removeIf(emitter -> !sendSafe(emitter, payload));
    }

    private boolean sendSafe(SseEmitter emitter, LiveEventPayload payload) {
        try {
            emitter.send(payload);
            return true;
        } catch (IOException e) {
            emitter.completeWithError(e);
            return false;
        }
    }

    private void closeMicrophone() {
        if (microphoneLine != null) {
            try {
                microphoneLine.stop();
                microphoneLine.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String extractText(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        int idx = json.indexOf("\"text\"");
        if (idx == -1) {
            return "";
        }
        String cleaned = json.substring(idx + 7)
                .replaceAll("[\":{}]", "")
                .trim();
        return cleaned;
    }
}

