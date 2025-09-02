package com.example.translationcallapp.websocket;

import com.example.translationcallapp.model.TwilioMediaMessage;
import com.example.translationcallapp.service.GoogleCloudService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@ServerEndpoint(value = "/media-stream", configurator = SpringConfigurator.class)
@Component
public class TwilioMediaStreamEndpoint {

    private static final Logger log = LoggerFactory.getLogger(TwilioMediaStreamEndpoint.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Store session information
    private static final ConcurrentMap<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    
    @Autowired
    private GoogleCloudService googleCloudService;

    @OnOpen
    public void onOpen(Session session) {
        log.info("WebSocket connection opened: {}", session.getId());
        
        // Initialize session info
        SessionInfo sessionInfo = new SessionInfo();
        sessionInfo.session = session;
        sessionInfo.sourceLanguage = "en-US";  // Default
        sessionInfo.targetLanguage = "es-ES";  // Default
        sessions.put(session.getId(), sessionInfo);
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        try {
            TwilioMediaMessage mediaMessage = objectMapper.readValue(message, TwilioMediaMessage.class);
            SessionInfo sessionInfo = sessions.get(session.getId());
            
            if (sessionInfo == null) {
                log.warn("No session info found for session: {}", session.getId());
                return;
            }

            String event = mediaMessage.getEvent();
            log.debug("Received event: {} for session: {}", event, session.getId());

            switch (event) {
                case "connected":
                    handleConnected(session);
                    break;
                    
                case "start":
                    handleStart(mediaMessage, sessionInfo);
                    break;
                    
                case "media":
                    handleMedia(mediaMessage, sessionInfo);
                    break;
                    
                case "mark":
                    handleMark(mediaMessage, sessionInfo);
                    break;
                    
                case "stop":
                    handleStop(sessionInfo);
                    break;
                    
                default:
                    log.debug("Unknown event type: {}", event);
            }

        } catch (Exception e) {
            log.error("Error processing WebSocket message", e);
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        log.info("WebSocket connection closed: {} - Reason: {}", session.getId(), closeReason.getReasonPhrase());
        sessions.remove(session.getId());
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        log.error("WebSocket error for session: {}", session.getId(), throwable);
        sessions.remove(session.getId());
    }

    private void handleConnected(Session session) {
        log.info("Twilio Media Stream connected for session: {}", session.getId());
        
        try {
            // Send acknowledgment
            String ackMessage = "{\"event\": \"connected\", \"protocol\": \"Call\"}";
            session.getBasicRemote().sendText(ackMessage);
        } catch (IOException e) {
            log.error("Error sending connected acknowledgment", e);
        }
    }

    private void handleStart(TwilioMediaMessage message, SessionInfo sessionInfo) {
        TwilioMediaMessage.StartPayload start = message.getStart();
        if (start != null) {
            sessionInfo.streamSid = start.getStreamSid();
            sessionInfo.callSid = start.getCallSid();
            
            TwilioMediaMessage.MediaFormat mediaFormat = start.getMediaFormat();
            if (mediaFormat != null) {
                sessionInfo.encoding = mediaFormat.getEncoding();
                sessionInfo.sampleRate = mediaFormat.getSampleRate();
                sessionInfo.channels = mediaFormat.getChannels();
            }
            
            log.info("Media stream started - StreamSid: {}, CallSid: {}, Encoding: {}, SampleRate: {}", 
                sessionInfo.streamSid, sessionInfo.callSid, sessionInfo.encoding, sessionInfo.sampleRate);
        }
    }

    private void handleMedia(TwilioMediaMessage message, SessionInfo sessionInfo) {
        TwilioMediaMessage.MediaPayload media = message.getMedia();
        if (media == null || media.getPayload() == null) {
            return;
        }

        try {
            // Decode base64 audio data
            byte[] audioData = Base64.getDecoder().decode(media.getPayload());
            
            // Only process if Google Cloud services are available
            if (googleCloudService.isInitialized()) {
                // Process audio for real-time translation
                googleCloudService.processAudioChunk(
                    audioData,
                    sessionInfo.sourceLanguage,
                    sessionInfo.targetLanguage,
                    translatedAudio -> {
                        try {
                            // Send translated audio back to Twilio
                            sendAudioToTwilio(translatedAudio, sessionInfo);
                        } catch (Exception e) {
                            log.error("Error sending translated audio", e);
                        }
                    }
                );
            } else {
                log.debug("Google Cloud services not initialized, skipping audio processing");
            }

        } catch (Exception e) {
            log.error("Error processing media payload", e);
        }
    }

    private void handleMark(TwilioMediaMessage message, SessionInfo sessionInfo) {
        TwilioMediaMessage.MarkPayload mark = message.getMark();
        if (mark != null) {
            log.debug("Received mark: {} for session: {}", mark.getName(), sessionInfo.session.getId());
        }
    }

    private void handleStop(SessionInfo sessionInfo) {
        log.info("Media stream stopped for session: {}", sessionInfo.session.getId());
        // Cleanup any resources if needed
    }

    private void sendAudioToTwilio(byte[] audioData, SessionInfo sessionInfo) throws IOException {
        if (audioData == null || audioData.length == 0) {
            return;
        }

        // Encode audio data to base64
        String base64Audio = Base64.getEncoder().encodeToString(audioData);
        
        // Create media message to send back to Twilio
        String mediaMessage = String.format(
            "{\"event\": \"media\", \"streamSid\": \"%s\", \"media\": {\"payload\": \"%s\"}}",
            sessionInfo.streamSid,
            base64Audio
        );

        // Send the message
        synchronized (sessionInfo.session) {
            if (sessionInfo.session.isOpen()) {
                sessionInfo.session.getBasicRemote().sendText(mediaMessage);
                log.debug("Sent translated audio chunk to Twilio");
            }
        }
    }

    // Method to update language settings for a session
    public void updateLanguageSettings(String sessionId, String sourceLanguage, String targetLanguage) {
        SessionInfo sessionInfo = sessions.get(sessionId);
        if (sessionInfo != null) {
            sessionInfo.sourceLanguage = sourceLanguage;
            sessionInfo.targetLanguage = targetLanguage;
            log.info("Updated language settings for session {}: {} -> {}", 
                sessionId, sourceLanguage, targetLanguage);
        }
    }

    // Inner class to store session information
    private static class SessionInfo {
        Session session;
        String streamSid;
        String callSid;
        String encoding = "mulaw";
        int sampleRate = 8000;
        int channels = 1;
        String sourceLanguage = "en-US";
        String targetLanguage = "es-ES";
    }
}