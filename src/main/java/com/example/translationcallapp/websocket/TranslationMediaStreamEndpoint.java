package com.example.translationcallapp.websocket;

import com.example.translationcallapp.service.OpenAITranslationService;
import com.example.translationcallapp.service.AudioFormatConversionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ServerEndpoint(
    value = "/media-stream/{conferenceName}", 
    configurator = SpringConfigurator.class
)
@Slf4j
public class TranslationMediaStreamEndpoint {

    @Autowired
    private OpenAITranslationService openAITranslationService;

    @Autowired
    private AudioFormatConversionService audioConversionService;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${media.stream.max.queue.size:10}")
    private int maxQueueSize;

    @Value("${translation.default.source.language:en-US}")
    private String defaultSourceLanguage;

    @Value("${translation.default.target.language:es-ES}")
    private String defaultTargetLanguage;

    @Value("${media.stream.processing.chunk-size:320}")
    private int audioChunkSize;
    
    // Track active sessions by WebSocket session ID
    private final ConcurrentHashMap<String, MediaSession> activeSessions = new ConcurrentHashMap<>();
    
    // Track processing queue to prevent overload
    private final ConcurrentHashMap<String, AtomicInteger> processingQueues = new ConcurrentHashMap<>();
    
    // Enhanced monitoring metrics
    private final AtomicLong totalSessionsCreated = new AtomicLong(0);
    private final AtomicLong totalAudioPacketsReceived = new AtomicLong(0);
    private final AtomicLong totalAudioPacketsDropped = new AtomicLong(0);
    private final AtomicLong totalTranslationRequests = new AtomicLong(0);

    @OnOpen
    public void onOpen(Session session, @PathParam("conferenceName") String conferenceName) {
        try {
            log.info("Media stream WebSocket opened for conference: {}", conferenceName);
            
            // Validate conference name
            if (!isValidConferenceName(conferenceName)) {
                log.warn("Invalid conference name: {}", conferenceName);
                session.close(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, "Invalid conference name"));
                return;
            }
            
            MediaSession mediaSession = new MediaSession(session, conferenceName);
            activeSessions.put(session.getId(), mediaSession);
            processingQueues.put(session.getId(), new AtomicInteger(0));
            totalSessionsCreated.incrementAndGet();
            
            log.info("Active media sessions: {}, Total created: {}", 
                    activeSessions.size(), totalSessionsCreated.get());
            
        } catch (Exception e) {
            log.error("Error opening media stream session for conference: {}", conferenceName, e);
            try {
                session.close(new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "Setup error"));
            } catch (Exception closeException) {
                log.error("Failed to close session after setup error", closeException);
            }
        }
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        try {
            MediaSession mediaSession = activeSessions.get(session.getId());
            if (mediaSession == null) {
                log.warn("No media session found for session ID: {}", session.getId());
                return;
            }

            JsonNode event = objectMapper.readTree(message);
            String eventType = event.has("event") ? event.get("event").asText() : null;

            if (eventType == null) {
                log.warn("Received event without 'event' field for conference: {}", 
                        mediaSession.getConferenceName());
                return;
            }

            switch (eventType) {
                case "connected":
                    handleConnected(event, mediaSession);
                    break;
                    
                case "start":
                    handleStart(event, mediaSession);
                    break;
                    
                case "media":
                    handleMedia(event, mediaSession, session.getId());
                    break;
                    
                case "mark":
                    handleMark(event, mediaSession);
                    break;
                    
                case "stop":
                    handleStop(event, mediaSession);
                    break;
                    
                default:
                    log.debug("Unhandled media stream event: {} for conference: {}", 
                             eventType, mediaSession.getConferenceName());
                    break;
            }

        } catch (Exception e) {
            MediaSession mediaSession = activeSessions.get(session.getId());
            String conferenceName = mediaSession != null ? mediaSession.getConferenceName() : "unknown";
            log.error("Error processing media stream message for conference: {}", conferenceName, e);
        }
    }

    @OnError
    public void onError(Session session, Throwable error) {
        MediaSession mediaSession = activeSessions.get(session.getId());
        String conferenceName = mediaSession != null ? mediaSession.getConferenceName() : "unknown";
        log.error("Media stream WebSocket error for conference: {}", conferenceName, error);
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        try {
            MediaSession mediaSession = activeSessions.remove(session.getId());
            processingQueues.remove(session.getId());
            
            if (mediaSession != null) {
                log.info("Media stream WebSocket closed for conference: {}, reason: {}, packets processed: {}", 
                        mediaSession.getConferenceName(), closeReason, mediaSession.getPacketsProcessed());
                log.info("Active media sessions: {}", activeSessions.size());
            }
            
        } catch (Exception e) {
            log.error("Error closing media stream session", e);
        }
    }

    /**
     * Handle WebSocket connected event
     */
    private void handleConnected(JsonNode event, MediaSession mediaSession) {
        try {
            String protocol = event.has("protocol") ? event.get("protocol").asText() : "unknown";
            String version = event.has("version") ? event.get("version").asText() : "unknown";
            
            log.info("Media stream connected - Conference: {}, Protocol: {}, Version: {}", 
                    mediaSession.getConferenceName(), protocol, version);
                    
        } catch (Exception e) {
            log.error("Error handling connected event for conference: {}", 
                     mediaSession.getConferenceName(), e);
        }
    }

    /**
     * Handle stream start event with enhanced participant mapping
     */
    private void handleStart(JsonNode event, MediaSession mediaSession) {
        try {
            String streamSid = event.has("streamSid") ? event.get("streamSid").asText() : null;
            String callSid = event.has("callSid") ? event.get("callSid").asText() : null;
            String tracks = event.has("tracks") ? event.get("tracks").toString() : "none";
            
            mediaSession.setStreamSid(streamSid);
            mediaSession.setCallSid(callSid);
            
            log.info("Media stream started - Conference: {}, StreamSID: {}, CallSID: {}, Tracks: {}", 
                    mediaSession.getConferenceName(), streamSid, callSid, tracks);

            // Get languages from query parameters or use defaults
            String sourceLanguage = extractLanguageFromSession(mediaSession, "source", defaultSourceLanguage);
            String targetLanguage = extractLanguageFromSession(mediaSession, "target", defaultTargetLanguage);

            // Initialize translation for this conference if not already active
            if (!openAITranslationService.isTranslationActive(mediaSession.getConferenceName())) {
                boolean started = openAITranslationService.startTranslation(
                    mediaSession.getConferenceName(), 
                    sourceLanguage, 
                    targetLanguage
                );
                
                if (started) {
                    log.info("Translation started for conference: {} ({} -> {})", 
                            mediaSession.getConferenceName(), sourceLanguage, targetLanguage);
                    mediaSession.setTranslationActive(true);
                } else {
                    log.warn("Failed to start translation for conference: {}", 
                            mediaSession.getConferenceName());
                }
            }
            
        } catch (Exception e) {
            log.error("Error handling start event for conference: {}", 
                     mediaSession.getConferenceName(), e);
        }
    }

    /**
     * Handle media (audio) event with enhanced processing
     */
    private void handleMedia(JsonNode event, MediaSession mediaSession, String sessionId) {
        try {
            totalAudioPacketsReceived.incrementAndGet();
            
            // Check processing queue to prevent overload
            AtomicInteger queueSize = processingQueues.get(sessionId);
            if (queueSize != null && queueSize.get() >= maxQueueSize) {
                log.warn("Processing queue full ({}/{}) for conference: {}, dropping audio packet", 
                        queueSize.get(), maxQueueSize, mediaSession.getConferenceName());
                totalAudioPacketsDropped.incrementAndGet();
                return;
            }

            JsonNode payload = event.get("payload");
            if (payload == null) {
                log.debug("Received media event without payload for conference: {}", 
                         mediaSession.getConferenceName());
                return;
            }

            // Enhanced null checks as recommended by Twilio
            String track = payload.has("track") ? payload.get("track").asText() : null;
            String chunk = payload.has("chunk") ? payload.get("chunk").asText() : null;
            String timestamp = payload.has("timestamp") ? payload.get("timestamp").asText() : null;
            String sequenceNumber = payload.has("sequenceNumber") ? payload.get("sequenceNumber").asText() : null;

            if (track == null || chunk == null) {
                log.debug("Media event missing required fields (track/chunk) for conference: {}", 
                         mediaSession.getConferenceName());
                return;
            }

            // Only process inbound audio (from participants to translation)
            if (!"inbound".equals(track)) {
                return;
            }

            if (chunk.isEmpty()) {
                return;
            }

            // Validate audio data before processing
            if (!audioConversionService.isValidAudioData(chunk)) {
                log.debug("Invalid audio data received for conference: {}", 
                         mediaSession.getConferenceName());
                return;
            }

            // Enhanced participant determination
            String participant = determineParticipant(mediaSession, payload);
            mediaSession.incrementPacketsProcessed();

            log.debug("Processing audio - Conference: {}, Track: {}, Participant: {}, Timestamp: {}, Seq: {}, Chunk size: {}", 
                     mediaSession.getConferenceName(), track, participant, timestamp, sequenceNumber, chunk.length());

            // Accumulate audio chunks for better translation quality
            mediaSession.addAudioChunk(chunk);

            // Process accumulated audio when we have enough data
            if (mediaSession.shouldProcessAccumulatedAudio(audioChunkSize)) {
                String accumulatedAudio = mediaSession.getAndClearAccumulatedAudio();
                
                // Increment queue counter
                if (queueSize != null) {
                    queueSize.incrementAndGet();
                }

                totalTranslationRequests.incrementAndGet();

                // Process audio asynchronously
                openAITranslationService.processAudioAsync(
                    mediaSession.getConferenceName(), 
                    participant, 
                    accumulatedAudio,
                    () -> {
                        // Decrement queue counter when processing completes
                        if (queueSize != null) {
                            queueSize.decrementAndGet();
                        }
                    }
                );
            }

        } catch (Exception e) {
            log.error("Error handling media event for conference: {}", 
                     mediaSession.getConferenceName(), e);
        }
    }

    /**
     * Handle mark event
     */
    private void handleMark(JsonNode event, MediaSession mediaSession) {
        try {
            String name = event.has("name") ? event.get("name").asText() : null;
            log.debug("Received mark event - Conference: {}, Name: {}", 
                     mediaSession.getConferenceName(), name);
                     
        } catch (Exception e) {
            log.error("Error handling mark event for conference: {}", 
                     mediaSession.getConferenceName(), e);
        }
    }

    /**
     * Handle stream stop event
     */
    private void handleStop(JsonNode event, MediaSession mediaSession) {
        try {
            log.info("Media stream stopped for conference: {}", mediaSession.getConferenceName());
            
            // Process any remaining accumulated audio
            String remainingAudio = mediaSession.getAndClearAccumulatedAudio();
            if (remainingAudio != null && !remainingAudio.isEmpty()) {
                openAITranslationService.processAudio(
                    mediaSession.getConferenceName(), 
                    "caller", 
                    remainingAudio
                );
            }
            
            // Stop translation for this conference
            if (mediaSession.isTranslationActive()) {
                boolean stopped = openAITranslationService.stopTranslation(mediaSession.getConferenceName());
                if (stopped) {
                    log.info("Translation stopped for conference: {}", mediaSession.getConferenceName());
                    mediaSession.setTranslationActive(false);
                }
            }
            
        } catch (Exception e) {
            log.error("Error handling stop event for conference: {}", 
                     mediaSession.getConferenceName(), e);
        }
    }

    /**
     * Enhanced participant determination with better mapping
     */
    private String determineParticipant(MediaSession mediaSession, JsonNode payload) {
        try {
            // Try to get participant info from payload if available
            if (payload.has("participantSid")) {
                String participantSid = payload.get("participantSid").asText();
                return "participant-" + participantSid;
            }
            
            // Try to correlate with call SID
            if (payload.has("callSid") && mediaSession.getCallSid() != null) {
                String payloadCallSid = payload.get("callSid").asText();
                if (mediaSession.getCallSid().equals(payloadCallSid)) {
                    return "caller";
                }
            }
            
            // Fallback to track-based determination
            String track = payload.has("track") ? payload.get("track").asText() : "unknown";
            return "inbound".equals(track) ? "caller" : "target";
            
        } catch (Exception e) {
            log.warn("Error determining participant for conference: {}, using default", 
                    mediaSession.getConferenceName(), e);
            return "caller";
        }
    }

    /**
     * Extract language configuration from WebSocket session
     */
    private String extractLanguageFromSession(MediaSession mediaSession, String type, String defaultValue) {
        try {
            // Parse query parameters from WebSocket URI if available
            Session session = mediaSession.getWebSocketSession();
            if (session != null && session.getRequestURI() != null) {
                String query = session.getRequestURI().getQuery();
                if (query != null) {
                    String[] params = query.split("&");
                    for (String param : params) {
                        String[] keyValue = param.split("=");
                        if (keyValue.length == 2) {
                            String key = keyValue[0];
                            String value = keyValue[1];
                            if ((type + "Language").equals(key) || (type + "_language").equals(key)) {
                                return value;
                            }
                        }
                    }
                }
            }
            
            return defaultValue;
            
        } catch (Exception e) {
            log.warn("Error extracting {} language from session, using default: {}", 
                    type, defaultValue, e);
            return defaultValue;
        }
    }

    /**
     * Validate conference name to prevent injection attacks
     */
    private boolean isValidConferenceName(String conferenceName) {
        if (conferenceName == null || conferenceName.trim().isEmpty()) {
            return false;
        }
        
        // Conference names should be alphanumeric with hyphens and underscores
        return conferenceName.matches("^[a-zA-Z0-9-_]+$") && conferenceName.length() <= 100;
    }

    /**
     * Get comprehensive monitoring statistics
     */
    public MediaStreamStats getStats() {
        return new MediaStreamStats(
            activeSessions.size(),
            totalSessionsCreated.get(),
            totalAudioPacketsReceived.get(),
            totalAudioPacketsDropped.get(),
            totalTranslationRequests.get(),
            getTotalQueueSize(),
            calculateDropRate()
        );
    }

    /**
     * Get active session count for monitoring
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    /**
     * Get processing queue status for monitoring
     */
    public int getTotalQueueSize() {
        return processingQueues.values().stream()
                .mapToInt(AtomicInteger::get)
                .sum();
    }

    /**
     * Calculate packet drop rate for monitoring
     */
    private double calculateDropRate() {
        long total = totalAudioPacketsReceived.get();
        if (total == 0) return 0.0;
        
        long dropped = totalAudioPacketsDropped.get();
        return (dropped * 100.0) / total;
    }

    /**
     * Enhanced media session data class
     */
    private static class MediaSession {
        private final Session webSocketSession;
        private final String conferenceName;
        private String streamSid;
        private String callSid;
        private boolean translationActive = false;
        private final AtomicLong packetsProcessed = new AtomicLong(0);
        private final StringBuilder accumulatedAudio = new StringBuilder();
        private final Object audioLock = new Object();

        public MediaSession(Session webSocketSession, String conferenceName) {
            this.webSocketSession = webSocketSession;
            this.conferenceName = conferenceName;
        }

        public void addAudioChunk(String audioChunk) {
            synchronized (audioLock) {
                accumulatedAudio.append(audioChunk);
            }
        }

        public boolean shouldProcessAccumulatedAudio(int chunkSize) {
            synchronized (audioLock) {
                return accumulatedAudio.length() >= chunkSize;
            }
        }

        public String getAndClearAccumulatedAudio() {
            synchronized (audioLock) {
                if (accumulatedAudio.length() == 0) {
                    return null;
                }
                String audio = accumulatedAudio.toString();
                accumulatedAudio.setLength(0);
                return audio;
            }
        }

        // Getters and setters
        public Session getWebSocketSession() { return webSocketSession; }
        public String getConferenceName() { return conferenceName; }
        public String getStreamSid() { return streamSid; }
        public void setStreamSid(String streamSid) { this.streamSid = streamSid; }
        public String getCallSid() { return callSid; }
        public void setCallSid(String callSid) { this.callSid = callSid; }
        public boolean isTranslationActive() { return translationActive; }
        public void setTranslationActive(boolean translationActive) { this.translationActive = translationActive; }
        public long getPacketsProcessed() { return packetsProcessed.get(); }
        public void incrementPacketsProcessed() { packetsProcessed.incrementAndGet(); }
    }

    /**
     * Enhanced media stream statistics DTO for monitoring
     */
    public static class MediaStreamStats {
        private final int activeSessions;
        private final long totalSessionsCreated;
        private final long totalPacketsReceived;
        private final long totalPacketsDropped;
        private final long totalTranslationRequests;
        private final int currentQueueSize;
        private final double dropRate;

        public MediaStreamStats(int activeSessions, long totalSessionsCreated, 
                               long totalPacketsReceived, long totalPacketsDropped,
                               long totalTranslationRequests, int currentQueueSize, double dropRate) {
            this.activeSessions = activeSessions;
            this.totalSessionsCreated = totalSessionsCreated;
            this.totalPacketsReceived = totalPacketsReceived;
            this.totalPacketsDropped = totalPacketsDropped;
            this.totalTranslationRequests = totalTranslationRequests;
            this.currentQueueSize = currentQueueSize;
            this.dropRate = dropRate;
        }

        // Getters
        public int getActiveSessions() { return activeSessions; }
        public long getTotalSessionsCreated() { return totalSessionsCreated; }
        public long getTotalPacketsReceived() { return totalPacketsReceived; }
        public long getTotalPacketsDropped() { return totalPacketsDropped; }
        public long getTotalTranslationRequests() { return totalTranslationRequests; }
        public int getCurrentQueueSize() { return currentQueueSize; }
        public double getDropRate() { return dropRate; }
    }
}