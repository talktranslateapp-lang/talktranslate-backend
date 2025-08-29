package com.example.translationcallapp.websocket;

import com.example.translationcallapp.service.GoogleCloudService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ServerEndpoint(value = "/api/streams", configurator = SpringConfigurator.class)
public class TwilioMediaStreamEndpoint {
    
    private static final Logger logger = LoggerFactory.getLogger(TwilioMediaStreamEndpoint.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    @Autowired
    private GoogleCloudService googleCloudService;
    
    // Session management
    private static final ConcurrentHashMap<String, Session> activeSessions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CallSession> callSessions = new ConcurrentHashMap<>();
    
    // Call session data
    private static class CallSession {
        public String sourceLanguage = "en-US";
        public String targetLanguage = "es-ES";
        public String streamSid;
        public String callSid;
        public boolean isActive = false;
        
        public CallSession(String streamSid, String callSid) {
            this.streamSid = streamSid;
            this.callSid = callSid;
            this.isActive = true;
        }
    }
    
    @OnOpen
    public void onOpen(Session session) {
        String sessionId = session.getId();
        activeSessions.put(sessionId, session);
        logger.info("WebSocket connection opened: {}", sessionId);
    }
    
    @OnMessage
    public void onMessage(String message, Session session) {
        try {
            JsonNode messageNode = objectMapper.readTree(message);
            String event = messageNode.get("event").asText();
            
            switch (event) {
                case "connected":
                    handleConnected(messageNode, session);
                    break;
                case "start":
                    handleStart(messageNode, session);
                    break;
                case "media":
                    handleMedia(messageNode, session);
                    break;
                case "stop":
                    handleStop(messageNode, session);
                    break;
                default:
                    logger.debug("Unhandled event: {}", event);
            }
            
        } catch (Exception e) {
            logger.error("Error processing WebSocket message", e);
        }
    }
    
    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        String sessionId = session.getId();
        activeSessions.remove(sessionId);
        
        // Clean up call session
        callSessions.values().removeIf(callSession -> 
            callSession.streamSid != null && callSession.streamSid.equals(sessionId));
        
        logger.info("WebSocket connection closed: {} - Reason: {}", 
                   sessionId, closeReason.getReasonPhrase());
    }
    
    @OnError
    public void onError(Session session, Throwable throwable) {
        logger.error("WebSocket error for session: {}", session.getId(), throwable);
    }
    
    private void handleConnected(JsonNode messageNode, Session session) {
        logger.info("Twilio Media Stream connected: {}", messageNode.get("protocol").asText());
        
        // Send back acknowledgment
        try {
            String response = objectMapper.writeValueAsString(new Object() {
                public final String event = "connected";
                public final String protocol = "Call";
                public final String version = "1.0.0";
            });
            session.getBasicRemote().sendText(response);
        } catch (Exception e) {
            logger.error("Error sending connected response", e);
        }
    }
    
    private void handleStart(JsonNode messageNode, Session session) {
        try {
            String streamSid = messageNode.get("streamSid").asText();
            String callSid = messageNode.get("callSid").asText();
            
            // Create call session
            CallSession callSession = new CallSession(streamSid, callSid);
            callSessions.put(session.getId(), callSession);
            
            logger.info("Media stream started - StreamSid: {}, CallSid: {}", streamSid, callSid);
            
            // Initialize Google Cloud services if needed
            if (!googleCloudService.isInitialized()) {
                logger.warn("Google Cloud services not initialized, translation may not work");
            }
            
        } catch (Exception e) {
            logger.error("Error handling stream start", e);
        }
    }
    
    private void handleMedia(JsonNode messageNode, Session session) {
        try {
            CallSession callSession = callSessions.get(session.getId());
            if (callSession == null || !callSession.isActive) {
                return;
            }
            
            String payloadBase64 = messageNode.get("media").get("payload").asText();
            byte[] audioData = Base64.getDecoder().decode(payloadBase64);
            
            // Process audio asynchronously to avoid blocking
            CompletableFuture.runAsync(() -> processAudio(audioData, callSession, session));
            
        } catch (Exception e) {
            logger.error("Error handling media message", e);
        }
    }
    
    private void handleStop(JsonNode messageNode, Session session) {
        String sessionId = session.getId();
        CallSession callSession = callSessions.get(sessionId);
        
        if (callSession != null) {
            callSession.isActive = false;
            logger.info("Media stream stopped - StreamSid: {}", callSession.streamSid);
        }
        
        callSessions.remove(sessionId);
    }
    
    private void processAudio(byte[] audioData, CallSession callSession, Session session) {
        try {
            if (!googleCloudService.isInitialized()) {
                logger.warn("Google Cloud services not initialized, skipping audio processing");
                return;
            }
            
            // Step 1: Speech-to-Text
            String transcript = googleCloudService.speechToText(audioData, callSession.sourceLanguage);
            
            if (transcript != null && !transcript.trim().isEmpty()) {
                logger.debug("Transcript: {}", transcript);
                
                // Step 2: Translation
                String sourceLanguageCode = googleCloudService.getTranslateLanguageCode(callSession.sourceLanguage);
                String targetLanguageCode = googleCloudService.getTranslateLanguageCode(callSession.targetLanguage);
                
                String translatedText = googleCloudService.translateText(
                    transcript, 
                    targetLanguageCode, 
                    sourceLanguageCode
                );
                
                if (translatedText != null && !translatedText.trim().isEmpty()) {
                    logger.debug("Translated: {}", translatedText);
                    
                    // Step 3: Text-to-Speech
                    byte[] synthesizedAudio = googleCloudService.textToSpeech(
                        translatedText, 
                        callSession.targetLanguage
                    );
                    
                    if (synthesizedAudio.length > 0) {
                        // Send synthesized audio back through the stream
                        sendAudioToTwilio(synthesizedAudio, session);
                    }
                }
            }
            
        } catch (Exception e) {
            logger.error("Error processing audio for translation", e);
        }
    }
    
    private void sendAudioToTwilio(byte[] audioData, Session session) {
        try {
            String audioBase64 = Base64.getEncoder().encodeToString(audioData);
            
            // Create media message to send back to Twilio
            String mediaMessage = objectMapper.writeValueAsString(new Object() {
                public final String event = "media";
                public final Object media = new Object() {
                    public final String payload = audioBase64;
                };
            });
            
            session.getBasicRemote().sendText(mediaMessage);
            
        } catch (Exception e) {
            logger.error("Error sending audio to Twilio", e);
        }
    }
    
    // Configuration methods for language settings
    public void updateLanguages(String sessionId, String sourceLanguage, String targetLanguage) {
        CallSession callSession = callSessions.get(sessionId);
        if (callSession != null) {
            callSession.sourceLanguage = sourceLanguage;
            callSession.targetLanguage = targetLanguage;
            logger.info("Updated languages for session {}: {} -> {}", 
                       sessionId, sourceLanguage, targetLanguage);
        }
    }
    
    // Get active sessions count for monitoring
    public int getActiveSessionsCount() {
        return activeSessions.size();
    }
    
    // Get active call sessions count for monitoring  
    public int getActiveCallSessionsCount() {
        return callSessions.size();
    }
}
