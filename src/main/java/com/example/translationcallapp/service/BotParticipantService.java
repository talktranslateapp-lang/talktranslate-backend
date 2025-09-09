package com.example.translationcallapp.service;

import com.twilio.Twilio;
import com.twilio.exception.ApiException;
import com.twilio.http.HttpMethod;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.rest.api.v2010.account.Conference;
import com.twilio.rest.api.v2010.account.conference.Participant;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Dial;
import com.twilio.twiml.voice.Say;
import com.twilio.type.PhoneNumber;
import com.twilio.jwt.accesstoken.AccessToken;
import com.twilio.jwt.accesstoken.VoiceGrant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class BotParticipantService {

    private static final Logger logger = LoggerFactory.getLogger(BotParticipantService.class);

    @Value("${twilio.account.sid}")
    private String twilioAccountSid;

    @Value("${twilio.auth.token}")
    private String twilioAuthToken;

    @Value("${twilio.phone.number}")
    private String twilioPhoneNumber;

    @Value("${twilio.api.key}")
    private String twilioApiKey;

    @Value("${twilio.api.secret}")
    private String twilioApiSecret;

    @Value("${twilio.twiml.app.sid}")
    private String twilioTwimlAppSid;

    @Value("${twilio.webhook.base.url:https://talktranslate-backend-production.up.railway.app}")
    private String webhookBaseUrl;

    // Rate limiting
    private final AtomicInteger activeBotCount = new AtomicInteger(0);
    private final AtomicInteger maxConcurrentBots = new AtomicInteger(50);
    private final AtomicLong lastRateLimitReset = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger hourlyCallCount = new AtomicInteger(0);
    private final int maxHourlyCallLimit = 1000;

    // Conference and participant tracking
    private final Map<String, Set<String>> activeConferences = new ConcurrentHashMap<>();
    private final Map<String, ParticipantMetadata> participantMetadata = new ConcurrentHashMap<>();
    private final Set<String> conferencesWithBots = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService cleanupExecutor = Executors.newScheduledThreadPool(2);

    // Statistics tracking
    private final AtomicInteger totalBotsCreated = new AtomicInteger(0);
    private final AtomicInteger totalConferencesCreated = new AtomicInteger(0);
    private final AtomicLong serviceStartTime = new AtomicLong(System.currentTimeMillis());

    public BotParticipantService() {
        // Initialize cleanup task
        cleanupExecutor.scheduleWithFixedDelay(this::cleanupStaleData, 5, 30, TimeUnit.MINUTES);
        cleanupExecutor.scheduleWithFixedDelay(this::resetHourlyLimits, 1, 1, TimeUnit.HOURS);
    }

    @PostConstruct
    private void initializeTwilio() {
        Twilio.init(twilioAccountSid, twilioAuthToken);
        logger.info("Twilio SDK initialized");
    }

    /**
     * Check if a bot is already in the conference
     */
    public boolean isBotInConference(String conferenceName) {
        return conferencesWithBots.contains(conferenceName);
    }

    /**
     * Generate Twilio access token for client
     */
    public String generateAccessToken(String identity) {
        try {
            // Use simplified token generation for SDK 10.x
            AccessToken.Builder builder = new AccessToken.Builder(
                twilioAccountSid,
                twilioApiKey,
                twilioApiSecret
            );
            builder.identity(identity);

            // Add Voice grant
            VoiceGrant voiceGrant = new VoiceGrant();
            voiceGrant.setOutgoingApplicationSid(twilioTwimlAppSid);
            voiceGrant.setIncomingAllow(true);
            builder.grant(voiceGrant);

            AccessToken accessToken = builder.build();
            return accessToken.toJwt();
        } catch (Exception e) {
            logger.error("Failed to generate access token for identity {}: {}", identity, e.getMessage());
            throw new RuntimeException("Failed to generate access token", e);
        }
    }

    /**
     * Store call data for webhook processing
     */
    public void storeCallData(String callId, String targetPhoneNumber, String sourceLanguage, String targetLanguage) {
        try {
            // Store call data in participantMetadata for webhook lookup
            ParticipantMetadata metadata = new ParticipantMetadata();
            metadata.setPhoneNumber(targetPhoneNumber);
            metadata.setSourceLanguage(sourceLanguage);
            metadata.setTargetLanguage(targetLanguage);
            metadata.setConferenceName(callId);
            metadata.setCallId(callId);
            metadata.setTimestamp(System.currentTimeMillis());
            
            participantMetadata.put(callId, metadata);
            
            logger.info("Stored call data for callId: {}, target: {}, languages: {} -> {}", 
                       callId, targetPhoneNumber, sourceLanguage, targetLanguage);
        } catch (Exception e) {
            logger.error("Failed to store call data for callId {}: {}", callId, e.getMessage());
            throw new RuntimeException("Failed to store call data", e);
        }
    }

    /**
     * Add translation bot to conference
     */
    public String addTranslationBot(String conferenceSid, String fromLanguage, String toLanguage) {
        try {
            if (!checkRateLimit()) {
                throw new RuntimeException("Rate limit exceeded. Please try again later.");
            }

            String webhookUrl = webhookBaseUrl + "/api/call/voice/incoming";
            
            Call call = Call.creator(
                new PhoneNumber(twilioPhoneNumber), // To (bot's virtual number)
                new PhoneNumber(twilioPhoneNumber), // From (same number for bot calls)
                URI.create(webhookUrl)
            )
            .setStatusCallback(URI.create(webhookBaseUrl + "/webhook/call-status"))
            .setStatusCallbackEvent(Arrays.asList("initiated", "ringing", "answered", "completed"))
            .setStatusCallbackMethod(HttpMethod.POST)
            .create();

            // Store bot metadata
            ParticipantMetadata botMetadata = new ParticipantMetadata();
            botMetadata.setCallSid(call.getSid());
            botMetadata.setConferenceName(conferenceSid);
            botMetadata.setSourceLanguage(fromLanguage);
            botMetadata.setTargetLanguage(toLanguage);
            botMetadata.setIsBot(true);
            botMetadata.setTimestamp(System.currentTimeMillis());
            
            participantMetadata.put(call.getSid(), botMetadata);
            
            // Track conference
            activeConferences.computeIfAbsent(conferenceSid, k -> ConcurrentHashMap.newKeySet()).add(call.getSid());
            
            activeBotCount.incrementAndGet();
            totalBotsCreated.incrementAndGet();
            hourlyCallCount.incrementAndGet();
            
            logger.info("Translation bot added: callSid={}, conference={}, languages={}->{}",
                       call.getSid(), conferenceSid, fromLanguage, toLanguage);
            
            // Track that this conference now has a bot
            conferencesWithBots.add(conferenceSid);

            return call.getSid();

        } catch (ApiException e) {
            logger.error("Twilio API error adding translation bot: {}", e.getMessage());
            throw new RuntimeException("Failed to create translation bot: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error adding translation bot: {}", e.getMessage());
            throw new RuntimeException("Failed to add translation bot: " + e.getMessage());
        }
    }

    /**
     * Add participant to conference
     */
    public String addParticipantToConference(String conferenceSid, String phoneNumber) {
        try {
            if (!checkRateLimit()) {
                throw new RuntimeException("Rate limit exceeded. Please try again later.");
            }

            // Pass conference name as URL parameter for reliable lookup
            String webhookUrl = webhookBaseUrl + "/api/call/voice/incoming?conferenceName=" + 
                               java.net.URLEncoder.encode(conferenceSid, "UTF-8");
            
            Call call = Call.creator(
                new PhoneNumber(phoneNumber),
                new PhoneNumber(twilioPhoneNumber),
                URI.create(webhookUrl)
            )
            .setStatusCallback(URI.create(webhookBaseUrl + "/webhook/call-status"))
            .setStatusCallbackEvent(Arrays.asList("initiated", "ringing", "answered", "completed"))
            .setStatusCallbackMethod(HttpMethod.POST)
            .create();

            // Store participant metadata
            ParticipantMetadata participantMeta = new ParticipantMetadata();
            participantMeta.setCallSid(call.getSid());
            participantMeta.setPhoneNumber(phoneNumber);
            participantMeta.setConferenceName(conferenceSid);
            participantMeta.setIsBot(false);
            participantMeta.setTimestamp(System.currentTimeMillis());
            
            participantMetadata.put(call.getSid(), participantMeta);
            
            // Track conference
            activeConferences.computeIfAbsent(conferenceSid, k -> ConcurrentHashMap.newKeySet()).add(call.getSid());
            
            hourlyCallCount.incrementAndGet();
            
            logger.info("Participant added: callSid={}, phone={}, conference={}",
                       call.getSid(), phoneNumber, conferenceSid);

            return call.getSid();

        } catch (ApiException e) {
            logger.error("Twilio API error adding participant: {}", e.getMessage());
            throw new RuntimeException("Failed to add participant: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error adding participant: {}", e.getMessage());
            throw new RuntimeException("Failed to add participant: " + e.getMessage());
        }
    }

    /**
     * Add bot to existing conference
     */
    public String addBot(String conferenceSid, String targetLanguage) {
        // For now, assume source language is English if not specified
        return addTranslationBot(conferenceSid, "en-US", targetLanguage);
    }

    /**
     * Get conference information
     */
    public Conference getConferenceInfo(String conferenceSid) {
        try {
            return Conference.fetcher(conferenceSid).fetch();
        } catch (Exception e) {
            logger.warn("Could not fetch conference info for {}: {}", conferenceSid, e.getMessage());
            return null;
        }
    }

    /**
     * Get conference participants
     */
    public List<Participant> getConferenceParticipants(String conferenceSid) {
        try {
            List<Participant> participants = new ArrayList<Participant>();
            for (Participant participant : Participant.reader(conferenceSid).read()) {
                participants.add(participant);
            }
            return participants;
        } catch (Exception e) {
            logger.warn("Could not fetch participants for conference {}: {}", conferenceSid, e.getMessage());
            return new ArrayList<Participant>();
        }
    }

    /**
     * End conference
     */
    public void endConference(String conferenceSid) {
        try {
            // Get and disconnect all participants
            List<Participant> participants = getConferenceParticipants(conferenceSid);
            for (Participant participant : participants) {
                try {
                    Participant.updater(conferenceSid, participant.getCallSid())
                        .setMuted(true)
                        .update();
                    
                    // Disconnect the call
                    Call.updater(participant.getCallSid())
                        .setStatus(Call.UpdateStatus.COMPLETED)
                        .update();
                        
                } catch (Exception e) {
                    logger.warn("Failed to disconnect participant {}: {}", participant.getCallSid(), e.getMessage());
                }
            }
            
            // Update the conference status
            Conference.updater(conferenceSid)
                .setStatus(Conference.UpdateStatus.COMPLETED)
                .update();
            
            // Clean up local tracking
            Set<String> callSids = activeConferences.remove(conferenceSid);
            if (callSids != null) {
                for (String callSid : callSids) {
                    ParticipantMetadata metadata = participantMetadata.remove(callSid);
                    if (metadata != null && metadata.isBot()) {
                        activeBotCount.decrementAndGet();
                    }
                }
            }
            
            // Remove from bot tracking
            conferencesWithBots.remove(conferenceSid);
            
            logger.info("Conference {} ended and cleaned up", conferenceSid);
            
        } catch (Exception e) {
            logger.error("Error ending conference {}: {}", conferenceSid, e.getMessage());
            throw new RuntimeException("Failed to end conference: " + e.getMessage());
        }
    }

    /**
     * Check if conference has active participants
     */
    public boolean hasActiveParticipants(String conferenceSid) {
        try {
            List<Participant> participants = getConferenceParticipants(conferenceSid);
            return participants.size() > 0;
        } catch (Exception e) {
            logger.warn("Error checking active participants for conference {}: {}", conferenceSid, e.getMessage());
            return false;
        }
    }

    /**
     * Remove all bot participants from conference
     */
    public void removeAllBotParticipants(String conferenceSid) {
        try {
            Set<String> callSids = activeConferences.get(conferenceSid);
            if (callSids != null) {
                for (String callSid : new HashSet<>(callSids)) {
                    ParticipantMetadata metadata = participantMetadata.get(callSid);
                    if (metadata != null && metadata.isBot()) {
                        try {
                            // Disconnect the bot call
                            Call.updater(callSid)
                                .setStatus(Call.UpdateStatus.COMPLETED)
                                .update();
                                
                            // Clean up tracking
                            participantMetadata.remove(callSid);
                            callSids.remove(callSid);
                            activeBotCount.decrementAndGet();
                            
                            logger.info("Removed bot participant: {}", callSid);
                        } catch (Exception e) {
                            logger.warn("Failed to remove bot participant {}: {}", callSid, e.getMessage());
                        }
                    }
                }
            }
            
            // Remove from bot tracking if no bots left
            conferencesWithBots.remove(conferenceSid);
        } catch (Exception e) {
            logger.error("Error removing bot participants from conference {}: {}", conferenceSid, e.getMessage());
        }
    }

    /**
     * Check rate limits
     */
    private boolean checkRateLimit() {
        // Reset hourly counters if needed
        long now = System.currentTimeMillis();
        if (now - lastRateLimitReset.get() > TimeUnit.HOURS.toMillis(1)) {
            if (lastRateLimitReset.compareAndSet(lastRateLimitReset.get(), now)) {
                hourlyCallCount.set(0);
                logger.info("Rate limit counters reset");
            }
        }
        
        // Check limits
        if (activeBotCount.get() >= maxConcurrentBots.get()) {
            logger.warn("Max concurrent bots limit reached: {}", maxConcurrentBots.get());
            return false;
        }
        
        if (hourlyCallCount.get() >= maxHourlyCallLimit) {
            logger.warn("Hourly call limit reached: {}", maxHourlyCallLimit);
            return false;
        }
        
        return true;
    }

    /**
     * Reset hourly limits
     */
    private void resetHourlyLimits() {
        hourlyCallCount.set(0);
        lastRateLimitReset.set(System.currentTimeMillis());
        logger.debug("Hourly rate limits reset");
    }

    /**
     * Cleanup stale data
     */
    private void cleanupStaleData() {
        long cutoffTime = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);
        
        participantMetadata.entrySet().removeIf(entry -> {
            if (entry.getValue().getTimestamp() < cutoffTime) {
                logger.debug("Removing stale participant metadata: {}", entry.getKey());
                if (entry.getValue().isBot()) {
                    activeBotCount.decrementAndGet();
                }
                return true;
            }
            return false;
        });
        
        // Clean up empty conference sets
        activeConferences.entrySet().removeIf(entry -> {
            if (entry.getValue().isEmpty()) {
                logger.debug("Removing empty conference: {}", entry.getKey());
                conferencesWithBots.remove(entry.getKey());
                return true;
            }
            return false;
        });
        
        logger.debug("Cleanup completed. Active bots: {}, Active conferences: {}", 
                    activeBotCount.get(), activeConferences.size());
    }

    /**
     * Get service statistics
     */
    public BotServiceStats getStats() {
        BotServiceStats stats = new BotServiceStats();
        stats.setActiveBots(activeBotCount.get());
        stats.setActiveConferences(activeConferences.size());
        stats.setMaxConcurrentBots(maxConcurrentBots.get());
        stats.setAvailableRateLimit(maxHourlyCallLimit - hourlyCallCount.get());
        stats.setTotalBotsCreated(totalBotsCreated.get());
        stats.setTotalConferencesCreated(totalConferencesCreated.get());
        stats.setUptimeMinutes((System.currentTimeMillis() - serviceStartTime.get()) / 60000);
        return stats;
    }

    /**
     * Check if service is healthy
     */
    public boolean isHealthy() {
        return activeBotCount.get() < maxConcurrentBots.get() * 0.9 && 
               hourlyCallCount.get() < maxHourlyCallLimit * 0.9;
    }

    /**
     * Get stored call data for webhook lookup
     */
    public Map<String, ParticipantMetadata> getStoredCallData() {
        return participantMetadata;
    }

    // Inner classes for data structures
    public static class ParticipantMetadata {
        private String callSid;
        private String phoneNumber;
        private String conferenceName;
        private String sourceLanguage;
        private String targetLanguage;
        private boolean isBot;
        private long timestamp;
        private String callId;

        // Getters and setters
        public String getCallSid() { return callSid; }
        public void setCallSid(String callSid) { this.callSid = callSid; }
        
        public String getPhoneNumber() { return phoneNumber; }
        public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
        
        public String getConferenceName() { return conferenceName; }
        public void setConferenceName(String conferenceName) { this.conferenceName = conferenceName; }
        
        public String getSourceLanguage() { return sourceLanguage; }
        public void setSourceLanguage(String sourceLanguage) { this.sourceLanguage = sourceLanguage; }
        
        public String getTargetLanguage() { return targetLanguage; }
        public void setTargetLanguage(String targetLanguage) { this.targetLanguage = targetLanguage; }
        
        public boolean isBot() { return isBot; }
        public void setIsBot(boolean isBot) { this.isBot = isBot; }
        
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        
        public String getCallId() { return callId; }
        public void setCallId(String callId) { this.callId = callId; }
    }

    public static class BotServiceStats {
        private int activeBots;
        private int activeConferences;
        private int maxConcurrentBots;
        private int availableRateLimit;
        private int totalBotsCreated;
        private int totalConferencesCreated;
        private long uptimeMinutes;

        // Getters and setters
        public int getActiveBots() { return activeBots; }
        public void setActiveBots(int activeBots) { this.activeBots = activeBots; }
        
        public int getActiveConferences() { return activeConferences; }
        public void setActiveConferences(int activeConferences) { this.activeConferences = activeConferences; }
        
        public int getMaxConcurrentBots() { return maxConcurrentBots; }
        public void setMaxConcurrentBots(int maxConcurrentBots) { this.maxConcurrentBots = maxConcurrentBots; }
        
        public int getAvailableRateLimit() { return availableRateLimit; }
        public void setAvailableRateLimit(int availableRateLimit) { this.availableRateLimit = availableRateLimit; }
        
        public int getTotalBotsCreated() { return totalBotsCreated; }
        public void setTotalBotsCreated(int totalBotsCreated) { this.totalBotsCreated = totalBotsCreated; }
        
        public int getTotalConferencesCreated() { return totalConferencesCreated; }
        public void setTotalConferencesCreated(int totalConferencesCreated) { this.totalConferencesCreated = totalConferencesCreated; }
        
        public long getUptimeMinutes() { return uptimeMinutes; }
        public void setUptimeMinutes(long uptimeMinutes) { this.uptimeMinutes = uptimeMinutes; }
    }
}