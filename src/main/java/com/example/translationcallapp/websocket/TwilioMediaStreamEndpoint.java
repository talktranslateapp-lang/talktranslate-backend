package com.example.translationcallapp.websocket;

import com.example.translationcallapp.service.OpenAITranslationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Component
@ServerEndpoint(value = "/media-stream", configurator = SpringConfigurator.class)
public class TwilioMediaStreamEndpoint {

    private static OpenAITranslationService translationService;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    
    // Audio processing buffers
    private static final ConcurrentMap<String, StringBuilder> audioBuffers = new ConcurrentHashMap<>();
    private static final int BUFFER_SIZE_THRESHOLD = 8000; // Adjust based on needs

    @Autowired
    public void setTranslationService(OpenAITranslationService translationService) {
        TwilioMediaStreamEndpoint.translationService = translationService;
    }

    @OnOpen
    public void onOpen(Session session) {
        log.info("WebSocket connection opened: {}", session.getId());
        sessions.put(session.getId(), session);
        audioBuffers.put(session.getId(), new StringBuilder());
        
        // Send initial message to Twilio
        sendMessage(session, createConnectedMessage());
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        try {
            JsonNode jsonNode = objectMapper.readTree(message);
            String event = jsonNode.get("event").asText();
            
            log.debug("Received event: {} for session: {}", event, session.getId());
            
            switch (event) {
                case "connected":
                    handleConnected(jsonNode, session);
                    break;
                case "start":
                    handleStart(jsonNode, session);
                    break;
                case "media":
                    handleMedia(jsonNode, session);
                    break;
                case "stop":
                    handleStop(jsonNode, session);
                    break;
                default:
                    log.warn("Unknown event type: {}", event);
            }
        } catch (Exception e) {
            log.error("Error processing message for session {}: {}", session.getId(), e.getMessage(), e);
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        log.info("WebSocket connection closed: {} - Reason: {}", session.getId(), closeReason.getReasonPhrase());
        cleanup(session);
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        log.error("WebSocket error for session {}: {}", session.getId(), throwable.getMessage(), throwable);
        cleanup(session);
    }

    private void handleConnected(JsonNode jsonNode, Session session) {
        log.info("Twilio connected for session: {}", session.getId());
        // Protocol established
    }

    private void handleStart(JsonNode jsonNode, Session session) {
        log.info("Media stream started for session: {}", session.getId());
        
        // Extract stream metadata
        JsonNode streamSid = jsonNode.get("streamSid");
        JsonNode accountSid = jsonNode.get("accountSid");
        JsonNode callSid = jsonNode.get("callSid");
        
        if (streamSid != null) {
            log.info("Stream SID: {} for session: {}", streamSid.asText(), session.getId());
        }
        
        // Initialize audio processing for this stream
        audioBuffers.put(session.getId(), new StringBuilder());
    }

    private void handleMedia(JsonNode jsonNode, Session session) {
        try {
            // Extract audio payload
            String payload = jsonNode.get("media").get("payload").asText();
            String track = jsonNode.get("media").get("track").asText();
            
            // Only process inbound audio (from caller)
            if ("inbound".equals(track)) {
                processAudioData(payload, session);
            }
            
        } catch (Exception e) {
            log.error("Error handling media for session {}: {}", session.getId(), e.getMessage(), e);
        }
    }

    private void handleStop(JsonNode jsonNode, Session session) {
        log.info("Media stream stopped for session: {}", session.getId());
        
        // Process any remaining audio in buffer
        processRemainingAudio(session);
        
        // Clean up session-specific resources
        cleanup(session);
    }

    private void processAudioData(String base64Payload, Session session) {
        try {
            // Decode the base64 audio data
            byte[] audioData = Base64.getDecoder().decode(base64Payload);
            
            // Buffer the audio data
            StringBuilder buffer = audioBuffers.get(session.getId());
            if (buffer != null) {
                buffer.append(base64Payload);
                
                // Process when buffer reaches threshold
                if (buffer.length() >= BUFFER_SIZE_THRESHOLD) {
                    processAudioBuffer(session);
                }
            }
            
        } catch (Exception e) {
            log.error("Error processing audio data for session {}: {}", session.getId(), e.getMessage(), e);
        }
    }

    private void processAudioBuffer(Session session) {
        StringBuilder buffer = audioBuffers.get(session.getId());
        if (buffer == null || buffer.length() == 0) {
            return;
        }

        try {
            String audioBase64 = buffer.toString();
            
            // Send to OpenAI for transcription and translation
            if (translationService != null) {
                translationService.processAudioStream(audioBase64, session.getId())
                    .thenAccept(translatedText -> {
                        if (translatedText != null && !translatedText.trim().isEmpty()) {
                            // Send translated text back to Twilio
                            sendTranslatedAudio(translatedText, session);
                        }
                    })
                    .exceptionally(throwable -> {
                        log.error("Error in translation service for session {}: {}", 
                                session.getId(), throwable.getMessage(), throwable);
                        return null;
                    });
            }
            
            // Clear the buffer
            buffer.setLength(0);
            
        } catch (Exception e) {
            log.error("Error processing audio buffer for session {}: {}", session.getId(), e.getMessage(), e);
        }
    }

    private void processRemainingAudio(Session session) {
        StringBuilder buffer = audioBuffers.get(session.getId());
        if (buffer != null && buffer.length() > 0) {
            log.info("Processing remaining audio buffer for session: {}", session.getId());
            processAudioBuffer(session);
        }
    }

    private void sendTranslatedAudio(String translatedText, Session session) {
        try {
            // Create TTS audio from translated text
            if (translationService != null) {
                translationService.textToSpeech(translatedText)
                    .thenAccept(audioBase64 -> {
                        if (audioBase64 != null) {
                            // Send audio back to Twilio
                            sendAudioToTwilio(audioBase64, session);
                        }
                    })
                    .exceptionally(throwable -> {
                        log.error("Error generating TTS for session {}: {}", 
                                session.getId(), throwable.getMessage(), throwable);
                        return null;
                    });
            }
        } catch (Exception e) {
            log.error("Error sending translated audio for session {}: {}", session.getId(), e.getMessage(), e);
        }
    }

    private void sendAudioToTwilio(String audioBase64, Session session) {
        try {
            // Create media message to send back to Twilio
            String mediaMessage = String.format(
                "{\"event\": \"media\", \"streamSid\": \"%s\", \"media\": {\"payload\": \"%s\"}}",
                session.getId(), audioBase64
            );
            
            sendMessage(session, mediaMessage);
            
        } catch (Exception e) {
            log.error("Error sending audio to Twilio for session {}: {}", session.getId(), e.getMessage(), e);
        }
    }

    private void sendMessage(Session session, String message) {
        try {
            if (session != null && session.isOpen()) {
                session.getBasicRemote().sendText(message);
            }
        } catch (IOException e) {
            log.error("Error sending message to session {}: {}", session.getId(), e.getMessage(), e);
        }
    }

    private String createConnectedMessage() {
        return "{\"event\": \"connected\", \"protocol\": \"Call\"}";
    }

    private void cleanup(Session session) {
        if (session != null) {
            String sessionId = session.getId();
            sessions.remove(sessionId);
            audioBuffers.remove(sessionId);
            
            log.info("Cleaned up resources for session: {}", sessionId);
        }
    }

    // Utility method to get active session count
    public static int getActiveSessionCount() {
        return sessions.size();
    }

    // Utility method to broadcast message to all sessions
    public static void broadcastMessage(String message) {
        sessions.values().forEach(session -> {
            try {
                if (session.isOpen()) {
                    session.getBasicRemote().sendText(message);
                }
            } catch (IOException e) {
                log.error("Error broadcasting message to session {}: {}", session.getId(), e.getMessage());
            }
        });
    }
}