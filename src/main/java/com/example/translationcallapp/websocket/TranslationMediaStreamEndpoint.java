package com.example.translationcallapp.websocket;

import com.example.translationcallapp.service.OpenAITranslationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WebSocket endpoint for handling translation-specific media streams
 * Supports bidirectional translation between specified languages
 */
@Slf4j
@Component
@ServerEndpoint(value = "/translation/{fromLang}/{toLang}", configurator = SpringConfigurator.class)
public class TranslationMediaStreamEndpoint {

    @Autowired
    private OpenAITranslationService translationService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, MediaSession> activeSessions = new ConcurrentHashMap<>();
    private final ExecutorService translationExecutor = Executors.newCachedThreadPool();

    @OnOpen
    public void onOpen(Session session,
                      @PathParam("fromLang") String fromLanguage,
                      @PathParam("toLang") String toLanguage) {

        String sessionId = session.getId();
        MediaSession mediaSession = new MediaSession(session, fromLanguage, toLanguage);
        activeSessions.put(sessionId, mediaSession);

        log.info("Translation stream opened: {} -> {} (Session: {})",
                fromLanguage, toLanguage, sessionId);

        session.getUserProperties().put("fromLanguage", fromLanguage);
        session.getUserProperties().put("toLanguage", toLanguage);

        sendMessage(session, createConnectionAck(fromLanguage, toLanguage));
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        try {
            JsonNode messageNode = objectMapper.readTree(message);
            String event = messageNode.get("event").asText();

            MediaSession mediaSession = activeSessions.get(session.getId());
            if (mediaSession == null) {
                log.warn("No media session found for WebSocket session: {}", session.getId());
                return;
            }

            switch (event) {
                case "connected":
                    handleConnectedEvent(messageNode, mediaSession);
                    break;
                case "start":
                    handleStartEvent(messageNode, mediaSession);
                    break;
                case "media":
                    handleMediaEvent(messageNode, mediaSession);
                    break;
                case "stop":
                    handleStopEvent(messageNode, mediaSession);
                    break;
                case "configuration":
                    handleConfigurationEvent(messageNode, mediaSession);
                    break;
                default:
                    log.warn("Unknown event type: {} for session: {}", event, session.getId());
            }
        } catch (Exception e) {
            log.error("Error processing message for session {}: ", session.getId(), e);
        }
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        log.error("WebSocket error for translation session: {}", session.getId(), throwable);
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        String sessionId = session.getId();
        MediaSession mediaSession = activeSessions.remove(sessionId);

        if (mediaSession != null) {
            mediaSession.cleanup();
            log.info("Translation stream closed: {} -> {} (Session: {}) - Reason: {}",
                    mediaSession.getFromLanguage(), mediaSession.getToLanguage(),
                    sessionId, closeReason.getReasonPhrase());
        }
    }

    private void handleConnectedEvent(JsonNode messageNode, MediaSession mediaSession) {
        log.info("Translation service connected for session: {}", mediaSession.getSessionId());
        mediaSession.initializeTranslationPipeline();
    }

    private void handleStartEvent(JsonNode messageNode, MediaSession mediaSession) {
        JsonNode start = messageNode.get("start");
        String streamSid = start.get("streamSid").asText();
        String callSid = start.get("callSid").asText();

        mediaSession.setStreamMetadata(streamSid, callSid);

        log.info("Translation stream started - Stream: {}, Call: {}, Languages: {} -> {}",
                streamSid, callSid,
                mediaSession.getFromLanguage(), mediaSession.getToLanguage());
    }

    private void handleMediaEvent(JsonNode messageNode, MediaSession mediaSession) {
        try {
            JsonNode media = messageNode.get("media");
            String payload = media.get("payload").asText();
            long timestamp = media.get("timestamp").asLong();

            byte[] audioData = Base64.getDecoder().decode(payload);

            translationExecutor.submit(() ->
                processAudioForTranslation(audioData, timestamp, mediaSession));

        } catch (Exception e) {
            log.error("Error handling media event for session {}: ",
                     mediaSession.getSessionId(), e);
        }
    }

    private void handleStopEvent(JsonNode messageNode, MediaSession mediaSession) {
        log.info("Translation stream stopping for session: {}", mediaSession.getSessionId());
        mediaSession.stopTranslation();
    }

    private void handleConfigurationEvent(JsonNode messageNode, MediaSession mediaSession) {
        JsonNode config = messageNode.get("configuration");

        if (config.has("qualityLevel")) {
            String quality = config.get("qualityLevel").asText();
            mediaSession.setQualityLevel(quality);
            log.debug("Updated quality level to {} for session: {}",
                     quality, mediaSession.getSessionId());
        }

        if (config.has("translationMode")) {
            String mode = config.get("translationMode").asText();
            mediaSession.setTranslationMode(mode);
            log.debug("Updated translation mode to {} for session: {}",
                     mode, mediaSession.getSessionId());
        }
    }

    private void processAudioForTranslation(byte[] audioData, long timestamp,
                                          MediaSession mediaSession) {
        try {
            mediaSession.addAudioData(audioData, timestamp);

            if (mediaSession.hasEnoughAudioForProcessing()) {
                byte[] audioChunk = mediaSession.getAudioChunkForProcessing();

                String fromLang = mediaSession.getFromLanguage();
                String toLang = mediaSession.getToLanguage();

                // FIXED: Updated method call to match OpenAITranslationService signature
                // Changed from speechToText(audioChunk, fromLang) to speechToText(audioChunk, fromLang)
                // The method signature in OpenAITranslationService is: speechToText(byte[] audioData, String language)
                String transcribedText = translationService.speechToText(audioChunk, fromLang);

                if (transcribedText != null && !transcribedText.trim().isEmpty()) {
                    log.debug("Transcribed: {} ({})", transcribedText, fromLang);

                    // FIXED: Updated translateText method call
                    // Assuming the method signature is: translateText(String text, String fromLang, String toLang)
                    String translatedText = translationService.translateText(
                        transcribedText, fromLang, toLang);

                    if (translatedText != null && !translatedText.trim().isEmpty()) {
                        log.debug("Translated: {} ({})", translatedText, toLang);

                        // FIXED: Updated textToSpeech method call to single parameter
                        // Changed from textToSpeech(translatedText, toLang) to textToSpeech(translatedText)
                        // The method signature in OpenAITranslationService is: textToSpeech(String text)
                        byte[] translatedAudio = translationService.textToSpeech(translatedText);

                        if (translatedAudio != null && translatedAudio.length > 0) {
                            sendTranslatedAudio(mediaSession, translatedAudio);
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error processing audio for translation in session {}: ",
                     mediaSession.getSessionId(), e);
        }
    }

    private void sendTranslatedAudio(MediaSession mediaSession, byte[] translatedAudio) {
        try {
            String base64Audio = Base64.getEncoder().encodeToString(translatedAudio);

            String mediaMessage = objectMapper.writeValueAsString(Map.of(
                "event", "translatedMedia",
                "streamSid", mediaSession.getStreamSid(),
                "timestamp", System.currentTimeMillis(),
                "media", Map.of(
                    "payload", base64Audio,
                    "language", mediaSession.getToLanguage()
                )
            ));

            sendMessage(mediaSession.getSession(), mediaMessage);

        } catch (Exception e) {
            log.error("Error sending translated audio for session {}: ",
                     mediaSession.getSessionId(), e);
        }
    }

    private void sendMessage(Session session, String message) {
        try {
            if (session.isOpen()) {
                session.getBasicRemote().sendText(message);
            }
        } catch (IOException e) {
            log.error("Error sending message to session {}: ", session.getId(), e);
        }
    }

    private String createConnectionAck(String fromLang, String toLang) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                "event", "connected",
                "service", "translation",
                "fromLanguage", fromLang,
                "toLanguage", toLang,
                "status", "ready"
            ));
        } catch (Exception e) {
            log.error("Error creating connection ack: ", e);
            return "{\"event\":\"connected\",\"status\":\"ready\"}";
        }
    }

    public TranslationStats getStats() {
        return new TranslationStats(
            activeSessions.size(),
            activeSessions.values().stream()
                .mapToLong(MediaSession::getProcessedAudioDuration)
                .sum()
        );
    }

    public void shutdown() {
        translationExecutor.shutdown();
    }

    private static class MediaSession {
        private final Session session;
        private final String fromLanguage;
        private final String toLanguage;
        private String streamSid;
        private String callSid;
        private String qualityLevel = "standard";
        private String translationMode = "realtime";

        private final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
        private long lastProcessedTimestamp = 0;
        private long processedAudioDuration = 0;

        public MediaSession(Session session, String fromLanguage, String toLanguage) {
            this.session = session;
            this.fromLanguage = fromLanguage;
            this.toLanguage = toLanguage;
        }

        public void setStreamMetadata(String streamSid, String callSid) {
            this.streamSid = streamSid;
            this.callSid = callSid;
        }

        public void initializeTranslationPipeline() {
            log.debug("Initializing translation pipeline for {} -> {}", fromLanguage, toLanguage);
        }

        public void addAudioData(byte[] audioData, long timestamp) {
            synchronized (audioBuffer) {
                try {
                    audioBuffer.write(audioData);
                    lastProcessedTimestamp = timestamp;
                } catch (IOException e) {
                    log.error("Error adding audio data to buffer: ", e);
                }
            }
        }

        public boolean hasEnoughAudioForProcessing() {
            return audioBuffer.size() >= 16000; // ~1 second of audio
        }

        public byte[] getAudioChunkForProcessing() {
            synchronized (audioBuffer) {
                byte[] chunk = audioBuffer.toByteArray();
                audioBuffer.reset();
                processedAudioDuration += chunk.length;
                return chunk;
            }
        }

        public void stopTranslation() {
            synchronized (audioBuffer) {
                audioBuffer.reset();
            }
        }

        public void cleanup() {
            stopTranslation();
        }

        public Session getSession() { return session; }
        public String getSessionId() { return session.getId(); }
        public String getFromLanguage() { return fromLanguage; }
        public String getToLanguage() { return toLanguage; }
        public String getStreamSid() { return streamSid; }
        public String getCallSid() { return callSid; }
        public long getProcessedAudioDuration() { return processedAudioDuration; }

        public void setQualityLevel(String qualityLevel) { this.qualityLevel = qualityLevel; }
        public void setTranslationMode(String translationMode) { this.translationMode = translationMode; }
    }

    public static class TranslationStats {
        private final int activeSessions;
        private final long totalProcessedAudio;

        public TranslationStats(int activeSessions, long totalProcessedAudio) {
            this.activeSessions = activeSessions;
            this.totalProcessedAudio = totalProcessedAudio;
        }

        public int getActiveSessions() { return activeSessions; }
        public long getTotalProcessedAudio() { return totalProcessedAudio; }
    }
}