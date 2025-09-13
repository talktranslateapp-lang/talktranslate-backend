package com.example.translationcallapp.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.net.URI;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TranslationWebSocketHandler implements WebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(TranslationWebSocketHandler.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${openai.api.key}")
    private String openAiApiKey;
    
    // Track active sessions
    private final Map<String, TranslationSession> activeSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        logger.info("Translation WebSocket connection established: {}", session.getId());
        
        // Extract parameters from session
        String conferenceName = getSessionParameter(session, "conferenceName");
        String fromLang = getSessionParameter(session, "fromLang");
        String toLang = getSessionParameter(session, "toLang");
        
        logger.info("Translation session starting: {} ({}→{})", conferenceName, fromLang, toLang);
        
        // Create translation session
        TranslationSession translationSession = new TranslationSession(session, conferenceName, fromLang, toLang);
        activeSessions.put(session.getId(), translationSession);
        
        // Initialize OpenAI connection
        translationSession.connectToOpenAI(openAiApiKey);
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        try {
            String payload = (String) message.getPayload();
            JsonNode messageNode = objectMapper.readTree(payload);
            
            TranslationSession translationSession = activeSessions.get(session.getId());
            if (translationSession == null) {
                logger.warn("No translation session found for WebSocket: {}", session.getId());
                return;
            }
            
            // Handle different Twilio Media Stream events
            String event = messageNode.get("event").asText();
            
            switch (event) {
                case "start":
                    logger.info("Media stream started for session: {}", session.getId());
                    translationSession.handleStreamStart(messageNode);
                    break;
                    
                case "media":
                    // Forward audio to OpenAI for translation
                    translationSession.handleAudioData(messageNode);
                    break;
                    
                case "stop":
                    logger.info("Media stream stopped for session: {}", session.getId());
                    translationSession.handleStreamStop();
                    break;
                    
                default:
                    logger.debug("Unhandled event type: {}", event);
            }
            
        } catch (Exception e) {
            logger.error("Error handling WebSocket message: {}", e.getMessage(), e);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        logger.error("WebSocket transport error for session {}: {}", session.getId(), exception.getMessage());
        cleanupSession(session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        logger.info("Translation WebSocket connection closed: {} ({})", session.getId(), closeStatus);
        cleanupSession(session.getId());
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }
    
    private String getSessionParameter(WebSocketSession session, String paramName) {
        URI uri = session.getUri();
        if (uri != null && uri.getQuery() != null) {
            String[] params = uri.getQuery().split("&");
            for (String param : params) {
                String[] keyValue = param.split("=");
                if (keyValue.length == 2 && keyValue[0].equals(paramName)) {
                    return keyValue[1];
                }
            }
        }
        return null;
    }
    
    private void cleanupSession(String sessionId) {
        TranslationSession session = activeSessions.remove(sessionId);
        if (session != null) {
            session.cleanup();
        }
    }
    
    // Inner class to manage individual translation sessions
    private static class TranslationSession {
        private final WebSocketSession twilioSession;
        private final String conferenceName;
        private final String fromLang;
        private final String toLang;
        private WebSocketSession openAiSession;
        private String streamSid;
        
        private static final Logger logger = LoggerFactory.getLogger(TranslationSession.class);
        
        public TranslationSession(WebSocketSession twilioSession, String conferenceName, String fromLang, String toLang) {
            this.twilioSession = twilioSession;
            this.conferenceName = conferenceName;
            this.fromLang = fromLang;
            this.toLang = toLang;
        }
        
        public void connectToOpenAI(String apiKey) {
            try {
                // For now, we'll implement a simple placeholder
                // In a full implementation, you'd connect to OpenAI Realtime API here
                logger.info("OpenAI connection initialized for translation: {}→{}", fromLang, toLang);
                
                // TODO: Implement actual OpenAI Realtime API connection
                // This would involve connecting to wss://api.openai.com/v1/realtime
                // and setting up the translation model with proper language parameters
                
            } catch (Exception e) {
                logger.error("Failed to connect to OpenAI: {}", e.getMessage(), e);
            }
        }
        
        public void handleStreamStart(JsonNode startMessage) {
            streamSid = startMessage.get("start").get("streamSid").asText();
            logger.info("Stream started with SID: {}", streamSid);
        }
        
        public void handleAudioData(JsonNode mediaMessage) {
            try {
                String audioPayload = mediaMessage.get("media").get("payload").asText();
                
                // Decode the base64 audio
                byte[] audioData = Base64.getDecoder().decode(audioPayload);
                
                // TODO: Send audio to OpenAI for translation and get response
                // For now, we'll just log that we received audio
                logger.debug("Received audio data: {} bytes", audioData.length);
                
                // Placeholder: In real implementation, you would:
                // 1. Send audioData to OpenAI Realtime API
                // 2. Receive translated audio response
                // 3. Send translated audio back to Twilio using sendTranslatedAudio()
                
            } catch (Exception e) {
                logger.error("Error processing audio data: {}", e.getMessage(), e);
            }
        }
        
        public void handleStreamStop() {
            logger.info("Stream stopped for conference: {}", conferenceName);
            cleanup();
        }
        
        private void sendTranslatedAudio(byte[] translatedAudio) {
            try {
                if (twilioSession.isOpen()) {
                    String base64Audio = Base64.getEncoder().encodeToString(translatedAudio);
                    
                    Map<String, Object> mediaEvent = new HashMap<>();
                    mediaEvent.put("event", "media");
                    mediaEvent.put("streamSid", streamSid);
                    
                    Map<String, String> media = new HashMap<>();
                    media.put("payload", base64Audio);
                    mediaEvent.put("media", media);
                    
                    String jsonMessage = new ObjectMapper().writeValueAsString(mediaEvent);
                    twilioSession.sendMessage(new TextMessage(jsonMessage));
                }
            } catch (Exception e) {
                logger.error("Failed to send translated audio: {}", e.getMessage(), e);
            }
        }
        
        public void cleanup() {
            if (openAiSession != null && openAiSession.isOpen()) {
                try {
                    openAiSession.close();
                } catch (IOException e) {
                    logger.error("Error closing OpenAI session: {}", e.getMessage());
                }
            }
        }
    }
}