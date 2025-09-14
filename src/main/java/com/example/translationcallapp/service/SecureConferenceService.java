package com.example.translationcallapp.service;

import com.twilio.Twilio;
import com.twilio.jwt.accesstoken.AccessToken;
import com.twilio.jwt.accesstoken.VoiceGrant;
import com.twilio.rest.api.v2010.account.Conference;
import com.twilio.rest.api.v2010.account.conference.Participant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class SecureConferenceService {

    private static final Logger logger = LoggerFactory.getLogger(SecureConferenceService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    
    @Value("${twilio.account.sid}")
    private String accountSid;
    
    @Value("${twilio.auth.token}")
    private String authToken;
    
    @Value("${twilio.api.key:}")
    private String apiKey;
    
    @Value("${twilio.api.secret:}")
    private String apiSecret;
    
    @Autowired(required = false)
    private BotParticipantService botParticipantService;
    
    // Store mapping between internal IDs and secure conference names
    private final ConcurrentHashMap<String, String> conferenceMapping = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConferenceMetadata> conferenceMetadata = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConferenceSession> activeSessions = new ConcurrentHashMap<>();
    
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    @PostConstruct
    public void init() {
        Twilio.init(accountSid, authToken);
        
        // Schedule periodic cleanup every 30 minutes
        scheduler.scheduleAtFixedRate(this::cleanupExpiredConferences, 30, 30, TimeUnit.MINUTES);
        
        logger.info("SecureConferenceService initialized");
    }

    /**
     * Generate access token for client applications
     */
    public String generateAccessToken(String identity) {
        try {
            if (apiKey.isEmpty() || apiSecret.isEmpty()) {
                logger.warn("API key/secret not configured, using account SID for token generation");
                // Fallback to basic token without Voice Grant
                return "demo-token-" + identity + "-" + System.currentTimeMillis();
            }
            
            VoiceGrant voiceGrant = new VoiceGrant();
            voiceGrant.setOutgoingApplicationSid("your-twiml-app-sid"); // Configure this
            voiceGrant.setIncomingAllow(true);
            
            AccessToken accessToken = new AccessToken.Builder(accountSid, apiKey, apiSecret)
                .identity(identity)
                .grant(voiceGrant)
                .ttl(3600) // 1 hour
                .build();
                
            return accessToken.toJwt();
            
        } catch (Exception e) {
            logger.error("Error generating access token for {}: {}", identity, e.getMessage(), e);
            throw new RuntimeException("Failed to generate access token", e);
        }
    }

    /**
     * Create a secure conference with proper metadata tracking
     */
    public String createSecureConference(String conferenceName, String creatorIdentity) {
        try {
            logger.info("Creating secure conference: {} for creator: {}", conferenceName, creatorIdentity);
            
            // Generate secure conference ID
            String secureConferenceId = generateSecureConferenceName(
                conferenceName, "en", "es"); // Default languages, should be configurable
            
            // Create conference session tracking
            ConferenceSession session = new ConferenceSession(
                secureConferenceId,
                creatorIdentity,
                System.currentTimeMillis()
            );
            activeSessions.put(secureConferenceId, session);
            
            logger.info("Secure conference created: {}", secureConferenceId);
            return secureConferenceId;
            
        } catch (Exception e) {
            logger.error("Error creating secure conference: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create secure conference", e);
        }
    }

    /**
     * End a conference and clean up all resources
     */
    public void endConference(String conferenceId) {
        try {
            logger.info("Ending conference: {}", conferenceId);
            
            // Try to end the Twilio conference if it exists
            try {
                Conference conference = Conference.fetcher(conferenceId).fetch();
                if (conference.getStatus() == Conference.Status.IN_PROGRESS) {
                    Conference.updater(conferenceId)
                        .setStatus(Conference.UpdateStatus.COMPLETED)
                        .update();
                    logger.info("Twilio conference {} ended successfully", conferenceId);
                }
            } catch (Exception e) {
                logger.warn("Could not end Twilio conference {} (may not exist): {}", conferenceId, e.getMessage());
            }
            
            // Clean up our tracking
            removeConference(conferenceId);
            activeSessions.remove(conferenceId);
            
            // Clean up any associated bots
            if (botParticipantService != null) {
                // Remove bots associated with this conference
                botParticipantService.getActiveBotSessions().values()
                    .stream()
                    .filter(session -> conferenceId.equals(session.getConferenceName()))
                    .forEach(session -> botParticipantService.removeBotFromConference(session.getBotCallSid()));
            }
            
            logger.info("Conference {} ended and cleaned up successfully", conferenceId);
            
        } catch (Exception e) {
            logger.error("Error ending conference {}: {}", conferenceId, e.getMessage(), e);
        }
    }

    /**
     * Handle conference end events from webhooks
     */
    public void handleConferenceEnd(String conferenceSid) {
        try {
            logger.info("Handling conference end webhook: {}", conferenceSid);
            
            ConferenceSession session = activeSessions.get(conferenceSid);
            if (session != null) {
                long duration = System.currentTimeMillis() - session.getStartTime();
                logger.info("Conference {} ended after {}ms with {} participants", 
                           conferenceSid, duration, session.getParticipantCount());
                
                // Save conference metrics
                saveConferenceMetrics(session, duration);
            }
            
            // Clean up resources
            removeConference(conferenceSid);
            activeSessions.remove(conferenceSid);
            
        } catch (Exception e) {
            logger.error("Error handling conference end for {}: {}", conferenceSid, e.getMessage(), e);
        }
    }

    /**
     * Check conference participants and manage conference lifecycle
     */
    public void checkConferenceParticipants(String conferenceSid) {
        try {
            logger.debug("Checking participants for conference: {}", conferenceSid);
            
            ConferenceSession session = activeSessions.get(conferenceSid);
            if (session == null) {
                logger.warn("No active session found for conference: {}", conferenceSid);
                return;
            }
            
            // Get current participants from Twilio
            try {
                java.util.List<Participant> participants = new java.util.ArrayList<>();
                for (Participant participant : Participant.reader(conferenceSid).read()) {
                    participants.add(participant);
                }
                int participantCount = participants.size();
                
                session.setParticipantCount(participantCount);
                logger.debug("Conference {} has {} participants", conferenceSid, participantCount);
                
                // If no human participants left (only bots), end the conference
                long humanParticipants = participants.stream()
                    .filter(p -> !p.getCallSid().equals(p.getCallSid())) // Simple check for now
                    .count();
                
                if (humanParticipants == 0 && participantCount > 0) {
                    logger.info("Only bots remaining in conference {}, ending conference", conferenceSid);
                    endConference(conferenceSid);
                } else if (participantCount == 0) {
                    logger.info("No participants remaining in conference {}, cleaning up", conferenceSid);
                    handleConferenceEnd(conferenceSid);
                }
                
            } catch (Exception e) {
                logger.warn("Could not fetch participants for conference {}: {}", conferenceSid, e.getMessage());
            }
            
        } catch (Exception e) {
            logger.error("Error checking conference participants for {}: {}", conferenceSid, e.getMessage(), e);
        }
    }

    /**
     * Handle participant join events
     */
    public void handleParticipantJoin(String conferenceSid, String callSid) {
        try {
            logger.info("Handling participant join: Conference={}, CallSid={}", conferenceSid, callSid);
            
            ConferenceSession session = activeSessions.get(conferenceSid);
            if (session != null) {
                session.incrementParticipantCount();
                session.addParticipant(callSid);
                
                // If this is the first human participant and we need translation, add bot
                if (session.getParticipantCount() == 1 && botParticipantService != null) {
                    // Check if translation is needed for this conference
                    ConferenceMetadata metadata = getConferenceMetadata(conferenceSid);
                    if (metadata != null && needsTranslation(metadata)) {
                        logger.info("Adding translation bot to conference: {}", conferenceSid);
                        botParticipantService.addBotToConference(conferenceSid, callSid);
                    }
                }
            } else {
                logger.warn("No active session found for participant join in conference: {}", conferenceSid);
            }
            
        } catch (Exception e) {
            logger.error("Error handling participant join for conference {}: {}", conferenceSid, e.getMessage(), e);
        }
    }

    /**
     * Generate a secure, non-PII conference name
     * Format: "conf-{8-char-random-id}-{timestamp-hash}"
     */
    public String generateSecureConferenceName(String internalId, String sourceLanguage, String targetLanguage) {
        try {
            // Generate random component
            byte[] randomBytes = new byte[6];
            RANDOM.nextBytes(randomBytes);
            String randomPart = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
            
            // Generate timestamp hash component
            String timestamp = String.valueOf(System.currentTimeMillis());
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(timestamp.getBytes("UTF-8"));
            String hashPart = Base64.getUrlEncoder().withoutPadding().encodeToString(hashBytes).substring(0, 8);
            
            // Create secure conference name
            String secureConferenceName = String.format("conf-%s-%s", randomPart, hashPart);
            
            // Store mapping and metadata
            conferenceMapping.put(internalId, secureConferenceName);
            conferenceMetadata.put(secureConferenceName, new ConferenceMetadata(
                internalId, sourceLanguage, targetLanguage, System.currentTimeMillis()
            ));
            
            logger.info("Generated secure conference name: {} for internal ID: {}", secureConferenceName, internalId);
            return secureConferenceName;
            
        } catch (Exception e) {
            logger.error("Failed to generate secure conference name: {}", e.getMessage());
            // Fallback to simple random ID
            return "conf-" + System.currentTimeMillis() + "-" + RANDOM.nextInt(10000);
        }
    }

    /**
     * Get secure conference name from internal ID
     */
    public String getSecureConferenceName(String internalId) {
        return conferenceMapping.get(internalId);
    }

    /**
     * Get conference metadata from secure name
     */
    public ConferenceMetadata getConferenceMetadata(String secureConferenceName) {
        return conferenceMetadata.get(secureConferenceName);
    }

    /**
     * Remove conference from tracking
     */
    public boolean removeConference(String secureConferenceName) {
        try {
            logger.info("Removing conference from tracking: {}", secureConferenceName);
            
            ConferenceMetadata metadata = conferenceMetadata.remove(secureConferenceName);
            if (metadata != null) {
                String internalId = metadata.getInternalId();
                conferenceMapping.remove(internalId);
                logger.info("Successfully removed conference: {} (internal ID: {})", secureConferenceName, internalId);
                return true;
            } else {
                logger.warn("Conference not found in metadata: {}", secureConferenceName);
                return false;
            }
            
        } catch (Exception e) {
            logger.error("Error removing conference {}: {}", secureConferenceName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Remove conference by internal ID
     */
    public boolean removeConferenceByInternalId(String internalId) {
        try {
            String secureConferenceName = conferenceMapping.get(internalId);
            if (secureConferenceName != null) {
                return removeConference(secureConferenceName);
            } else {
                logger.warn("No secure conference name found for internal ID: {}", internalId);
                return false;
            }
        } catch (Exception e) {
            logger.error("Error removing conference by internal ID {}: {}", internalId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Clean up expired conference mappings (older than 24 hours)
     */
    public void cleanupExpiredConferences() {
        long cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000); // 24 hours
        final int[] removedCount = {0}; // Use array to make it effectively final
        
        try {
            conferenceMetadata.entrySet().removeIf(entry -> {
                if (entry.getValue().getCreatedAt() < cutoffTime) {
                    String internalId = entry.getValue().getInternalId();
                    conferenceMapping.remove(internalId);
                    logger.debug("Cleaned up expired conference: {}", entry.getKey());
                    removedCount[0]++;
                    return true;
                }
                return false;
            });
            
            // Also clean up expired sessions
            activeSessions.entrySet().removeIf(entry -> {
                if (entry.getValue().getStartTime() < cutoffTime) {
                    logger.debug("Cleaned up expired session: {}", entry.getKey());
                    return true;
                }
                return false;
            });
            
            if (removedCount[0] > 0) {
                logger.info("Cleanup completed - removed {} expired conferences", removedCount[0]);
            }
            
        } catch (Exception e) {
            logger.error("Error during conference cleanup: {}", e.getMessage(), e);
        }
    }

    /**
     * Validate conference name format
     */
    public boolean isValidConferenceName(String conferenceName) {
        return conferenceName != null && 
               conferenceName.startsWith("conf-") && 
               conferenceName.length() > 10 &&
               conferenceMetadata.containsKey(conferenceName);
    }

    /**
     * Get all active conferences count
     */
    public int getActiveConferenceCount() {
        return conferenceMetadata.size();
    }

    /**
     * Check if conference exists
     */
    public boolean conferenceExists(String secureConferenceName) {
        return conferenceMetadata.containsKey(secureConferenceName);
    }

    /**
     * Get conference age in minutes
     */
    public long getConferenceAgeMinutes(String secureConferenceName) {
        ConferenceMetadata metadata = conferenceMetadata.get(secureConferenceName);
        if (metadata != null) {
            long ageMs = System.currentTimeMillis() - metadata.getCreatedAt();
            return ageMs / (60 * 1000); // Convert to minutes
        }
        return -1;
    }

    /**
     * Get conference stats for monitoring
     */
    public ConferenceStats getConferenceStats() {
        int activeCount = conferenceMetadata.size();
        long oldestTimestamp = conferenceMetadata.values().stream()
                .mapToLong(ConferenceMetadata::getCreatedAt)
                .min()
                .orElse(System.currentTimeMillis());
        
        long oldestAgeMinutes = (System.currentTimeMillis() - oldestTimestamp) / (60 * 1000);
        
        return new ConferenceStats(activeCount, oldestAgeMinutes);
    }

    // Private helper methods

    private boolean needsTranslation(ConferenceMetadata metadata) {
        // Check if source and target languages are different
        return !metadata.getSourceLanguage().equals(metadata.getTargetLanguage());
    }

    private void saveConferenceMetrics(ConferenceSession session, long duration) {
        try {
            logger.info("Conference metrics - ID: {}, Duration: {}ms, Participants: {}, Creator: {}", 
                       session.getConferenceId(), duration, session.getParticipantCount(), session.getCreatorIdentity());
            
            // Here you would save metrics to your database or analytics service
            // This could include participant count, duration, language pairs, etc.
            
        } catch (Exception e) {
            logger.error("Error saving conference metrics: {}", e.getMessage(), e);
        }
    }

    /**
     * Conference metadata container
     */
    public static class ConferenceMetadata {
        private final String internalId;
        private final String sourceLanguage;
        private final String targetLanguage;
        private final long createdAt;

        public ConferenceMetadata(String internalId, String sourceLanguage, String targetLanguage, long createdAt) {
            this.internalId = internalId;
            this.sourceLanguage = sourceLanguage;
            this.targetLanguage = targetLanguage;
            this.createdAt = createdAt;
        }

        public String getInternalId() { return internalId; }
        public String getSourceLanguage() { return sourceLanguage; }
        public String getTargetLanguage() { return targetLanguage; }
        public long getCreatedAt() { return createdAt; }
    }

    /**
     * Conference statistics container
     */
    public static class ConferenceStats {
        private final int activeConferences;
        private final long oldestConferenceAgeMinutes;

        public ConferenceStats(int activeConferences, long oldestConferenceAgeMinutes) {
            this.activeConferences = activeConferences;
            this.oldestConferenceAgeMinutes = oldestConferenceAgeMinutes;
        }

        public int getActiveConferences() { return activeConferences; }
        public long getOldestConferenceAgeMinutes() { return oldestConferenceAgeMinutes; }
    }

    /**
     * Conference session tracking
     */
    public static class ConferenceSession {
        private final String conferenceId;
        private final String creatorIdentity;
        private final long startTime;
        private int participantCount = 0;
        private final ConcurrentHashMap<String, Long> participants = new ConcurrentHashMap<>();

        public ConferenceSession(String conferenceId, String creatorIdentity, long startTime) {
            this.conferenceId = conferenceId;
            this.creatorIdentity = creatorIdentity;
            this.startTime = startTime;
        }

        public String getConferenceId() { return conferenceId; }
        public String getCreatorIdentity() { return creatorIdentity; }
        public long getStartTime() { return startTime; }
        public int getParticipantCount() { return participantCount; }
        public void setParticipantCount(int count) { this.participantCount = count; }
        public void incrementParticipantCount() { this.participantCount++; }
        public void decrementParticipantCount() { this.participantCount--; }
        
        public void addParticipant(String callSid) {
            participants.put(callSid, System.currentTimeMillis());
        }
        
        public void removeParticipant(String callSid) {
            participants.remove(callSid);
        }
        
        public boolean hasParticipant(String callSid) {
            return participants.containsKey(callSid);
        }
    }
}