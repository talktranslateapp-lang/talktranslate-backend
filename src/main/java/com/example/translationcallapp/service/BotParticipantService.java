package com.example.translationcallapp.service;

import com.twilio.Twilio;
import com.twilio.exception.ApiException;
import com.twilio.http.HttpMethod;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.rest.api.v2010.account.Conference;
import com.twilio.rest.api.v2010.account.conference.Participant;
import com.twilio.type.PhoneNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class BotParticipantService {

    private static final Logger logger = LoggerFactory.getLogger(BotParticipantService.class);
    private static final int MAX_PARTICIPANTS_PER_PAGE = 50;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    @Value("${twilio.account.sid}")
    private String accountSid;

    @Value("${twilio.auth.token}")
    private String authToken;

    @Value("${twilio.phone.number}")
    private String twilioPhoneNumber;

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${app.bot.max-concurrent-bots:100}")
    private int maxConcurrentBots;

    @Value("${app.bot.reconnect-attempts:3}")
    private int maxReconnectAttempts;

    @Value("${twilio.rate-limiting.calls-per-minute:60}")
    private int maxCallsPerMinute;

    @Autowired
    private TwilioSecurityService twilioSecurityService;

    // Thread pool for async operations
    private ExecutorService executorService;
    
    // Rate limiting
    private final Semaphore rateLimiter = new Semaphore(60);
    private final ScheduledExecutorService rateLimiterReset = Executors.newSingleThreadScheduledExecutor();
    
    // Bot participant tracking
    private final ConcurrentHashMap<String, BotParticipantInfo> activeBots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> conferenceBotsMap = new ConcurrentHashMap<>();
    
    // Participant metadata tracking (replacement for getFrom/getTo)
    private final ConcurrentHashMap<String, ParticipantMetadata> participantMetadata = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        try {
            Twilio.init(accountSid, authToken);
            
            // Initialize thread pool
            executorService = Executors.newFixedThreadPool(10);
            
            // Reset rate limiter every minute
            rateLimiterReset.scheduleAtFixedRate(() -> {
                rateLimiter.release(maxCallsPerMinute - rateLimiter.availablePermits());
            }, 1, 1, TimeUnit.MINUTES);
            
            logger.info("BotParticipantService initialized with max {} concurrent bots", maxConcurrentBots);
            
        } catch (Exception e) {
            logger.error("Failed to initialize BotParticipantService: {}", e.getMessage(), e);
            throw new RuntimeException("Service initialization failed", e);
        }
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (executorService != null && !executorService.isShutdown()) {
                executorService.shutdown();
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            }
            
            if (rateLimiterReset != null && !rateLimiterReset.isShutdown()) {
                rateLimiterReset.shutdown();
            }
            
            // Cleanup any remaining bot participants
            cleanupAllBotParticipants();
            
            logger.info("BotParticipantService cleanup completed");
            
        } catch (Exception e) {
            logger.error("Error during BotParticipantService cleanup: {}", e.getMessage(), e);
        }
    }

    /**
     * Add translation bot - implements missing method for CallController
     */
    public String addTranslationBot(String conferenceSid, String fromLanguage, String toLanguage) {
        return createBotParticipant(conferenceSid, toLanguage, fromLanguage);
    }

    /**
     * Add participant to conference - implements missing method for CallController
     */
    public String addParticipantToConference(String conferenceSid, String phoneNumber) {
        try {
            logger.info("Adding participant {} to conference {}", phoneNumber, conferenceSid);

            // Check rate limiting
            if (!rateLimiter.tryAcquire()) {
                throw new RuntimeException("Rate limit exceeded for participant creation");
            }

            String webhookUrl = baseUrl + "/webhook/participant?conferenceName=" + conferenceSid;
            String statusCallbackUrl = baseUrl + "/webhook/participant/status";

            // Create the participant call using Twilio 10.x API
            Call call = Call.creator(
                    new PhoneNumber(phoneNumber), // To
                    new PhoneNumber(twilioPhoneNumber), // From
                    URI.create(webhookUrl)
            )
            .setMethod(HttpMethod.POST)
            .setStatusCallback(URI.create(statusCallbackUrl))
            .setStatusCallbackEvent(Arrays.asList(
                "initiated",
                "ringing", 
                "answered",
                "completed",
                "failed"
            ))
            .setStatusCallbackMethod(HttpMethod.POST)
            .setTimeout(DEFAULT_TIMEOUT_SECONDS)
            .create();

            // Store participant metadata
            participantMetadata.put(call.getSid(), new ParticipantMetadata(
                call.getSid(), phoneNumber, twilioPhoneNumber, conferenceSid, false, LocalDateTime.now()
            ));

            logger.info("Participant call created with SID: {} for conference: {}", 
                       call.getSid(), conferenceSid);
            
            return call.getSid();

        } catch (ApiException e) {
            logger.error("Twilio API error creating participant: {} (Code: {})", e.getMessage(), e.getCode());
            throw new RuntimeException("Failed to create participant: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Error creating participant for conference {}: {}", conferenceSid, e.getMessage(), e);
            throw new RuntimeException("Failed to create participant", e);
        }
    }

    /**
     * Add bot - implements missing method for CallController
     */
    public String addBot(String conferenceSid, String targetLanguage) {
        return createBotParticipant(conferenceSid, targetLanguage, "english");
    }

    /**
     * Get conference info - implements missing method for CallController
     */
    public Conference getConferenceInfo(String conferenceSid) {
        return getConference(conferenceSid);
    }

    /**
     * Creates a bot participant with Twilio 10.x API
     */
    public CompletableFuture<String> createBotParticipantAsync(String conferenceName, 
                                                              String targetLanguage, 
                                                              String sourceLanguage) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return createBotParticipant(conferenceName, targetLanguage, sourceLanguage);
            } catch (Exception e) {
                logger.error("Async bot creation failed for conference {}: {}", conferenceName, e.getMessage());
                throw new RuntimeException(e);
            }
        }, executorService);
    }

    /**
     * Creates a bot participant call using Twilio 10.x API
     */
    public String createBotParticipant(String conferenceName, String targetLanguage, String sourceLanguage) {
        // Validate inputs
        if (!StringUtils.hasText(conferenceName)) {
            throw new IllegalArgumentException("Conference name cannot be empty");
        }
        if (!isValidLanguage(targetLanguage) || !isValidLanguage(sourceLanguage)) {
            throw new IllegalArgumentException("Invalid language specified");
        }

        // Check rate limiting
        if (!rateLimiter.tryAcquire()) {
            throw new RuntimeException("Rate limit exceeded for bot creation");
        }

        // Check concurrent bot limit
        if (activeBots.size() >= maxConcurrentBots) {
            throw new RuntimeException("Maximum concurrent bot limit reached");
        }

        try {
            logger.info("Creating bot participant for conference: {}, target: {}, source: {}", 
                       conferenceName, targetLanguage, sourceLanguage);

            // Generate secure webhook URL with token
            String webhookUrl = buildSecureWebhookUrl(conferenceName, targetLanguage, sourceLanguage);
            String statusCallbackUrl = baseUrl + "/webhook/bot/status";

            // Create the bot call using Twilio 10.x API
            Call call = Call.creator(
                    new PhoneNumber(twilioPhoneNumber), // To
                    new PhoneNumber(twilioPhoneNumber), // From
                    URI.create(webhookUrl)
            )
            .setMethod(HttpMethod.POST)
            .setStatusCallback(URI.create(statusCallbackUrl))
            .setStatusCallbackEvent(Arrays.asList(
                "initiated",
                "ringing", 
                "answered",
                "completed",
                "failed"
            ))
            .setStatusCallbackMethod(HttpMethod.POST)
            .setTimeout(DEFAULT_TIMEOUT_SECONDS)
            .create();

            // Track the bot participant
            BotParticipantInfo botInfo = new BotParticipantInfo(
                call.getSid(), conferenceName, targetLanguage, sourceLanguage, LocalDateTime.now()
            );
            activeBots.put(call.getSid(), botInfo);
            
            // Update conference -> bots mapping
            conferenceBotsMap.computeIfAbsent(conferenceName, k -> ConcurrentHashMap.newKeySet())
                           .add(call.getSid());

            // Store participant metadata for bot detection
            participantMetadata.put(call.getSid(), new ParticipantMetadata(
                call.getSid(), twilioPhoneNumber, twilioPhoneNumber, conferenceName, true, LocalDateTime.now()
            ));

            logger.info("Bot participant call created with SID: {} for conference: {}", 
                       call.getSid(), conferenceName);
            
            return call.getSid();

        } catch (ApiException e) {
            logger.error("Twilio API error creating bot participant: {} (Code: {})", e.getMessage(), e.getCode());
            throw new RuntimeException("Failed to create bot participant: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Error creating bot participant for conference {}: {}", conferenceName, e.getMessage(), e);
            throw new RuntimeException("Failed to create bot participant", e);
        }
    }

    /**
     * Removes a bot participant using Twilio 10.x API
     */
    public void removeBotParticipant(String conferenceSid, String participantCallSid) {
        try {
            logger.info("Removing bot participant {} from conference {}", participantCallSid, conferenceSid);

            // In Twilio 10.x, use the participant call SID directly
            Participant.deleter(conferenceSid, participantCallSid).delete();

            // Clean up tracking
            BotParticipantInfo botInfo = activeBots.remove(participantCallSid);
            participantMetadata.remove(participantCallSid);
            
            if (botInfo != null) {
                Set<String> conferenceBots = conferenceBotsMap.get(botInfo.getConferenceName());
                if (conferenceBots != null) {
                    conferenceBots.remove(participantCallSid);
                    if (conferenceBots.isEmpty()) {
                        conferenceBotsMap.remove(botInfo.getConferenceName());
                    }
                }
            }

            logger.info("Bot participant {} successfully removed from conference {}", 
                       participantCallSid, conferenceSid);

        } catch (ApiException e) {
            logger.error("Twilio API error removing bot participant {}: {} (Code: {})", 
                        participantCallSid, e.getMessage(), e.getCode());
            throw new RuntimeException("Failed to remove bot participant", e);
        } catch (Exception e) {
            logger.error("Error removing bot participant {} from conference {}: {}", 
                        participantCallSid, conferenceSid, e.getMessage(), e);
        }
    }

    /**
     * Updates bot participant using Twilio 10.x API
     */
    public void updateBotParticipant(String conferenceSid, String participantCallSid, boolean muted) {
        try {
            logger.info("Updating bot participant {} in conference {}, muted: {}", 
                       participantCallSid, conferenceSid, muted);

            Participant.updater(conferenceSid, participantCallSid)
                    .setMuted(muted)
                    .update();

            logger.info("Bot participant {} updated successfully", participantCallSid);

        } catch (ApiException e) {
            logger.error("Twilio API error updating bot participant {}: {} (Code: {})", 
                        participantCallSid, e.getMessage(), e.getCode());
            throw new RuntimeException("Failed to update bot participant", e);
        } catch (Exception e) {
            logger.error("Error updating bot participant {} in conference {}: {}", 
                        participantCallSid, conferenceSid, e.getMessage(), e);
            throw new RuntimeException("Failed to update bot participant", e);
        }
    }

    /**
     * Gets conference information with Twilio 10.x API
     */
    @Cacheable(value = "conferences", key = "#conferenceSid")
    public Conference getConference(String conferenceSid) {
        try {
            return Conference.fetcher(conferenceSid).fetch();
        } catch (ApiException e) {
            if (e.getCode() == 20404) { // Conference not found
                logger.debug("Conference {} not found", conferenceSid);
                return null;
            }
            logger.error("Twilio API error fetching conference {}: {} (Code: {})", 
                        conferenceSid, e.getMessage(), e.getCode());
            throw new RuntimeException("Failed to fetch conference", e);
        } catch (Exception e) {
            logger.error("Error fetching conference {}: {}", conferenceSid, e.getMessage());
            return null;
        }
    }

    /**
     * Lists all participants using Twilio 10.x API with proper pagination
     */
    public List<Participant> getConferenceParticipants(String conferenceSid) {
        try {
            logger.debug("Fetching participants for conference: {}", conferenceSid);
            
            List<Participant> allParticipants = new ArrayList<>();
            
            // Twilio 10.x uses ResourceSet which implements Iterable
            Iterable<Participant> participants = Participant.reader(conferenceSid)
                    .limit(MAX_PARTICIPANTS_PER_PAGE)
                    .read();
            
            for (Participant participant : participants) {
                allParticipants.add(participant);
            }
            
            logger.debug("Found {} participants in conference {}", allParticipants.size(), conferenceSid);
            return allParticipants;
            
        } catch (ApiException e) {
            if (e.getCode() == 20404) {
                logger.debug("Conference {} not found when fetching participants", conferenceSid);
                return new ArrayList<>();
            }
            logger.error("Twilio API error fetching participants for conference {}: {} (Code: {})", 
                        conferenceSid, e.getMessage(), e.getCode());
            throw new RuntimeException("Failed to fetch conference participants", e);
        } catch (Exception e) {
            logger.error("Error fetching participants for conference {}: {}", conferenceSid, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Enhanced bot participant detection - uses metadata instead of getFrom/getTo
     */
    public List<String> findBotParticipants(String conferenceSid) {
        try {
            List<Participant> participants = getConferenceParticipants(conferenceSid);
            
            return participants.stream()
                    .filter(this::isBotParticipant)
                    .map(Participant::getCallSid)
                    .collect(Collectors.toList());
            
        } catch (Exception e) {
            logger.error("Error finding bot participants in conference {}: {}", conferenceSid, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Improved bot detection logic for Twilio 10.x - uses stored metadata
     */
    private boolean isBotParticipant(Participant participant) {
        try {
            // Check if it's in our tracking
            if (activeBots.containsKey(participant.getCallSid())) {
                return true;
            }
            
            // Check stored metadata
            ParticipantMetadata metadata = participantMetadata.get(participant.getCallSid());
            if (metadata != null) {
                return metadata.isBot();
            }
            
            return false;
                   
        } catch (Exception e) {
            logger.debug("Error checking if participant {} is bot: {}", 
                        participant.getCallSid(), e.getMessage());
            return false;
        }
    }

    /**
     * Removes all bot participants with proper error handling
     */
    @CacheEvict(value = "conferences", key = "#conferenceSid")
    public void removeAllBotParticipants(String conferenceSid) {
        Set<String> botCallSids = conferenceBotsMap.get(conferenceSid);
        
        if (botCallSids == null || botCallSids.isEmpty()) {
            logger.debug("No tracked bot participants found for conference {}", conferenceSid);
            return;
        }

        List<CompletableFuture<Void>> removals = botCallSids.stream()
                .map(callSid -> CompletableFuture.runAsync(() -> {
                    try {
                        removeBotParticipant(conferenceSid, callSid);
                    } catch (Exception e) {
                        logger.warn("Failed to remove bot participant {}: {}", callSid, e.getMessage());
                    }
                }, executorService))
                .collect(Collectors.toList());

        try {
            // Wait for all removals to complete
            CompletableFuture.allOf(removals.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);
                    
            logger.info("Removed {} bot participants from conference {}", 
                       removals.size(), conferenceSid);
                       
        } catch (Exception e) {
            logger.error("Error during bulk bot removal for conference {}: {}", 
                        conferenceSid, e.getMessage());
        }
    }

    /**
     * Enhanced conference activity check for Twilio 10.x
     */
    public boolean hasActiveParticipants(String conferenceSid) {
        try {
            Conference conference = getConference(conferenceSid);
            if (conference == null) {
                return false;
            }
            
            // Check if conference is active and has participants
            return Conference.Status.IN_PROGRESS.equals(conference.getStatus()) && 
                   !getConferenceParticipants(conferenceSid).isEmpty();
                   
        } catch (Exception e) {
            logger.error("Error checking active participants for conference {}: {}", 
                        conferenceSid, e.getMessage());
            return false;
        }
    }

    /**
     * Enhanced conference ending with proper cleanup
     */
    @CacheEvict(value = "conferences", key = "#conferenceSid")
    public void endConference(String conferenceSid) {
        try {
            logger.info("Ending conference: {}", conferenceSid);
            
            // First remove all bot participants
            removeAllBotParticipants(conferenceSid);
            
            // Then remove human participants
            List<Participant> participants = getConferenceParticipants(conferenceSid);
            
            for (Participant participant : participants) {
                if (!isBotParticipant(participant)) {
                    try {
                        Participant.deleter(conferenceSid, participant.getCallSid()).delete();
                        participantMetadata.remove(participant.getCallSid());
                    } catch (Exception e) {
                        logger.warn("Failed to remove participant {}: {}", 
                                   participant.getCallSid(), e.getMessage());
                    }
                }
            }
            
            logger.info("Conference {} ended successfully", conferenceSid);
            
        } catch (Exception e) {
            logger.error("Error ending conference {}: {}", conferenceSid, e.getMessage());
        }
    }

    /**
     * Health check method
     */
    public boolean isHealthy() {
        try {
            // Try a simple API call to verify connectivity
            Twilio.getRestClient().getAccountSid();
            return true;
        } catch (Exception e) {
            logger.error("Health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get bot participant statistics
     */
    public BotParticipantStats getStats() {
        return new BotParticipantStats(
            activeBots.size(),
            conferenceBotsMap.size(),
            maxConcurrentBots,
            rateLimiter.availablePermits()
        );
    }

    // Helper methods

    private String buildSecureWebhookUrl(String conferenceName, String targetLanguage, String sourceLanguage) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String token = twilioSecurityService.generateWebhookToken(conferenceName + ":" + timestamp);
        
        return String.format("%s/webhook/bot?conferenceName=%s&targetLanguage=%s&sourceLanguage=%s&timestamp=%s&token=%s",
                baseUrl, conferenceName, targetLanguage, sourceLanguage, timestamp, token);
    }

    private boolean isValidLanguage(String language) {
        if (!StringUtils.hasText(language)) {
            return false;
        }
        
        // Define supported languages
        Set<String> supportedLanguages = Set.of(
            "english", "spanish", "french", "german", "italian", 
            "portuguese", "chinese", "japanese", "korean", "arabic"
        );
        
        return supportedLanguages.contains(language.toLowerCase());
    }

    private void cleanupAllBotParticipants() {
        logger.info("Cleaning up all bot participants...");
        
        activeBots.keySet().parallelStream().forEach(callSid -> {
            try {
                BotParticipantInfo botInfo = activeBots.get(callSid);
                if (botInfo != null) {
                    // Try to end the call gracefully using Twilio 10.x API
                    Call.updater(callSid).setStatus(Call.UpdateStatus.COMPLETED).update();
                }
            } catch (Exception e) {
                logger.debug("Failed to cleanup bot participant {}: {}", callSid, e.getMessage());
            }
        });
        
        activeBots.clear();
        conferenceBotsMap.clear();
        participantMetadata.clear();
    }

    // Inner classes for data structures

    public static class BotParticipantInfo {
        private final String callSid;
        private final String conferenceName;
        private final String targetLanguage;
        private final String sourceLanguage;
        private final LocalDateTime createdAt;

        public BotParticipantInfo(String callSid, String conferenceName, 
                                String targetLanguage, String sourceLanguage, 
                                LocalDateTime createdAt) {
            this.callSid = callSid;
            this.conferenceName = conferenceName;
            this.targetLanguage = targetLanguage;
            this.sourceLanguage = sourceLanguage;
            this.createdAt = createdAt;
        }

        // Getters
        public String getCallSid() { return callSid; }
        public String getConferenceName() { return conferenceName; }
        public String getTargetLanguage() { return targetLanguage; }
        public String getSourceLanguage() { return sourceLanguage; }
        public LocalDateTime getCreatedAt() { return createdAt; }
    }

    public static class BotParticipantStats {
        private final int activeBots;
        private final int activeConferences;
        private final int maxConcurrentBots;
        private final int availableRateLimit;

        public BotParticipantStats(int activeBots, int activeConferences, 
                                 int maxConcurrentBots, int availableRateLimit) {
            this.activeBots = activeBots;
            this.activeConferences = activeConferences;
            this.maxConcurrentBots = maxConcurrentBots;
            this.availableRateLimit = availableRateLimit;
        }

        // Getters
        public int getActiveBots() { return activeBots; }
        public int getActiveConferences() { return activeConferences; }
        public int getMaxConcurrentBots() { return maxConcurrentBots; }
        public int getAvailableRateLimit() { return availableRateLimit; }
    }

    // New class to replace getFrom/getTo functionality
    public static class ParticipantMetadata {
        private final String callSid;
        private final String fromNumber;
        private final String toNumber;
        private final String conferenceSid;
        private final boolean isBot;
        private final LocalDateTime createdAt;

        public ParticipantMetadata(String callSid, String fromNumber, String toNumber, 
                                 String conferenceSid, boolean isBot, LocalDateTime createdAt) {
            this.callSid = callSid;
            this.fromNumber = fromNumber;
            this.toNumber = toNumber;
            this.conferenceSid = conferenceSid;
            this.isBot = isBot;
            this.createdAt = createdAt;
        }

        // Getters
        public String getCallSid() { return callSid; }
        public String getFromNumber() { return fromNumber; }
        public String getToNumber() { return toNumber; }
        public String getConferenceSid() { return conferenceSid; }
        public boolean isBot() { return isBot; }
        public LocalDateTime getCreatedAt() { return createdAt; }
    }
}