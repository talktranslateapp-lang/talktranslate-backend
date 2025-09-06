package com.example.translationcallapp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SecureConferenceService {

    private static final Logger logger = LoggerFactory.getLogger(SecureConferenceService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    
    // Store mapping between internal IDs and secure conference names
    private final ConcurrentHashMap<String, String> conferenceMapping = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConferenceMetadata> conferenceMetadata = new ConcurrentHashMap<>();

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
     * Clean up expired conference mappings (older than 24 hours)
     */
    public void cleanupExpiredConferences() {
        long cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000); // 24 hours
        
        conferenceMetadata.entrySet().removeIf(entry -> {
            if (entry.getValue().getCreatedAt() < cutoffTime) {
                String internalId = entry.getValue().getInternalId();
                conferenceMapping.remove(internalId);
                logger.info("Cleaned up expired conference: {}", entry.getKey());
                return true;
            }
            return false;
        });
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
}