package com.example.translationcallapp.websocket;

import com.example.translationcallapp.service.OpenAITranslationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket endpoint for handling Twilio Media Streams
 * Receives real-time audio data from Twilio and processes it for translation
 */
@Slf4j
@Component
@ServerEndpoint(value = "/media-stream", configurator = SpringConfigurator.class)
public class TwilioMediaStreamEndpoint {

    @Autowired
    private OpenAITranslationService translationService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Session> activeSessions = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session) {
        String sessionId = session.getId();
        activeSessions.put(sessionId, session);
        log.info("Media stream WebSocket opened for session: {}", sessionId);
        
        // Send acknowledgment to Twilio
        sendMessage(session, createAckMessage());
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        try {
            JsonNode messageNode = objectMapper.readTree(message);
            String event = messageNode.get("event").asText();
            
            switch (event) {
                case "connected":
                    handleConnectedEvent(messageNode, session);
                    break;
                case "start":
                    handleStartEvent(messageNode, session);
                    break;
                case "media":
                    handleMediaEvent(messageNode, session);
                    break;
                case "stop":
                    handleStopEvent(messageNode, session);
                    break;
                default:
                    log.warn("Unknown event type: {}", event);
            }
        } catch (Exception e) {
            log.error("Error processing message: ", e);
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        String sessionId = session.getId();
        activeSessions.remove(sessionId);
        log.info("Media stream WebSocket closed for session: {} - Reason: {}", 
                sessionId, closeReason.getReasonPhrase());
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        log.error("WebSocket error for session: {}", session.getId(), throwable);
    }

    /**
     * Handle the connected event from Twilio
     */
    private void handleConnectedEvent(JsonNode messageNode, Session session) {
        log.info("Twilio Media Stream connected for session: {}", session.getId());
        String protocol = messageNode.get("protocol").asText();
        String version = messageNode.get("version").asText();
        log.debug("Protocol: {}, Version: {}", protocol, version);
    }

    /**
     * Handle the start event which contains stream metadata
     */
    private void handleStartEvent(JsonNode messageNode, Session session) {
        JsonNode start = messageNode.get("start");
        String streamSid = start.get("streamSid").asText();
        String accountSid = start.get("accountSid").asText();
        String callSid = start.get("callSid").asText();
        
        log.info("Media stream started - Stream SID: {}, Call SID: {}", streamSid, callSid);
        
        // Store stream metadata in session
        session.getUserProperties().put("streamSid", streamSid);
        session.getUserProperties().put("callSid", callSid);
        session.getUserProperties().put("accountSid", accountSid);
    }

    /**
     * Handle incoming media (audio) data
     */
    private void handleMediaEvent(JsonNode messageNode, Session session) {
        try {
            JsonNode media = messageNode.get("media");
            String payload = media.get("payload").asText();
            
            // Decode base64 audio data
            byte[] audioData = Base64.getDecoder().decode(payload);
            
            // Process audio for translation
            processAudioForTranslation(audioData, session);
            
        } catch (Exception e) {
            log.error("Error processing media event: ", e);
        }
    }

    /**
     * Handle the stop event
     */
    private void handleStopEvent(JsonNode messageNode, Session session) {
        String streamSid = (String) session.getUserProperties().get("streamSid");
        log.info("Media stream stopped for Stream SID: {}", streamSid);
        
        // Clean up any ongoing translation processes
        cleanupTranslationSession(session);
    }

    /**
     * Process audio data for translation
     */
    private void processAudioForTranslation(byte[] audioData, Session session) {
        try {
            // This would typically accumulate audio data until we have enough
            // for processing, then send to OpenAI for speech-to-text
            
            String callSid = (String) session.getUserProperties().get("callSid");
            
            // For now, just log that we received audio data
            log.debug("Received {} bytes of audio data for call: {}", audioData.length, callSid);
            
            // TODO: Implement actual translation pipeline:
            // 1. Accumulate audio chunks
            // 2. Send to OpenAI Speech-to-Text
            // 3. Translate text
            // 4. Convert back to speech
            // 5. Send translated audio back to Twilio
            
        } catch (Exception e) {
            log.error("Error processing audio for translation: ", e);
        }
    }

    /**
     * Send a message to the WebSocket session
     */
    private void sendMessage(Session session, String message) {
        try {
            if (session.isOpen()) {
                session.getBasicRemote().sendText(message);
            }
        } catch (IOException e) {
            log.error("Error sending message to session {}: ", session.getId(), e);
        }
    }

    /**
     * Create acknowledgment message for Twilio
     */
    private String createAckMessage() {
        return "{\"event\":\"connected\",\"protocol\":\"Call\"}";
    }

    /**
     * Clean up translation session resources
     */
    private void cleanupTranslationSession(Session session) {
        String callSid = (String) session.getUserProperties().get("callSid");
        log.info("Cleaning up translation session for call: {}", callSid);
        
        // TODO: Stop any ongoing translation processes
        // Clean up temporary files, close OpenAI connections, etc.
    }

    /**
     * Send translated audio back to Twilio
     */
    private void sendTranslatedAudio(Session session, byte[] translatedAudioData) {
        try {
            String base64Audio = Base64.getEncoder().encodeToString(translatedAudioData);
            
            String mediaMessage = String.format(
                "{\"event\":\"media\",\"streamSid\":\"%s\",\"media\":{\"payload\":\"%s\"}}",
                session.getUserProperties().get("streamSid"),
                base64Audio
            );
            
            sendMessage(session, mediaMessage);
            
        } catch (Exception e) {
            log.error("Error sending translated audio: ", e);
        }
    }

    /**
     * Get active session count for monitoring
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    /**
     * Close all active sessions (for shutdown)
     */
    public void closeAllSessions() {
        activeSessions.values().forEach(session -> {
            try {
                if (session.isOpen()) {
                    session.close(new CloseReason(CloseReason.CloseCodes.GOING_AWAY, "Server shutdown"));
                }
            } catch (IOException e) {
                log.error("Error closing session: ", e);
            }
        });
        activeSessions.clear();
    }
}