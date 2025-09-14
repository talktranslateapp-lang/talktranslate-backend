package com.example.translationcallapp.service;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.type.PhoneNumber;
import com.twilio.type.Twiml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Simplified service for managing bot participants in translation conferences
 * Uses basic Twilio Call API for maximum compatibility
 */
@Service
public class BotParticipantService {
    
    private static final Logger logger = LoggerFactory.getLogger(BotParticipantService.class);
    
    @Value("${twilio.account.sid}")
    private String accountSid;
    
    @Value("${twilio.auth.token}")
    private String authToken;
    
    @Value("${twilio.phone.number}")
    private String twilioPhoneNumber;
    
    @Value("${app.webhook.base.url}")
    private String webhookBaseUrl;
    
    // Track active bots and their associated calls/conferences
    private final Map<String, BotSession> activeBotSessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    @PostConstruct
    public void init() {
        Twilio.init(accountSid, authToken);
        
        // Schedule periodic cleanup of stale bot sessions
        scheduler.scheduleAtFixedRate(this::cleanupStaleBotSessions, 5, 5, TimeUnit.MINUTES);
        
        logger.info("BotParticipantService initialized with webhook base URL: {}", webhookBaseUrl);
    }

    /**
     * Add a translation bot to the specified conference
     * Simplified version using basic Call API
     */
    public String addBotToConference(String conferenceName, String humanCallSid) {
        try {
            logger.info("Adding bot to conference: {} for human call: {}", conferenceName, humanCallSid);
            
            // Create TwiML that will make the bot join the conference
            String twimlContent = String.format(
                "<Response><Dial><Conference startConferenceOnEnter=\"false\" endConferenceOnExit=\"false\">%s</Conference></Dial></Response>",
                conferenceName
            );
            
            // Create bot call with simplified parameters
            Call botCall = Call.creator(
                    new PhoneNumber(twilioPhoneNumber), // To
                    new PhoneNumber(twilioPhoneNumber), // From
                    new Twiml(twimlContent)
                )
                .setUrl(URI.create(webhookBaseUrl + "/api/call/translation-bot"))
                .setStatusCallback(URI.create(webhookBaseUrl + "/webhook/call-status"))
                .setTimeout(30)
                .create();

            String botCallSid = botCall.getSid();
            logger.info("Bot call created successfully: {}", botCallSid);
            
            // Create and store bot session for tracking
            BotSession botSession = new BotSession(
                botCallSid,
                conferenceName,
                humanCallSid,
                System.currentTimeMillis()
            );
            activeBotSessions.put(botCallSid, botSession);
            
            // Schedule bot health check
            scheduleeBotHealthCheck(botCallSid, 30);
            
            return botCallSid;
            
        } catch (Exception e) {
            logger.error("Error adding bot to conference {}: {}", conferenceName, e.getMessage(), e);
            throw new RuntimeException("Failed to add bot to conference", e);
        }
    }

    /**
     * Create a bot participant - simplified version
     */
    public String createBotParticipant(String conferenceSid) {
        try {
            logger.info("Creating bot participant in conference: {}", conferenceSid);
            return addBotToConference(conferenceSid, null);
        } catch (Exception e) {
            logger.error("Error creating bot participant in conference {}: {}", conferenceSid, e.getMessage(), e);
            throw new RuntimeException("Failed to create bot participant", e);
        }
    }

    /**
     * Remove bot from conference and clean up resources
     */
    public void removeBotFromConference(String botCallSid) {
        try {
            logger.info("Removing bot from conference: {}", botCallSid);
            
            BotSession botSession = activeBotSessions.get(botCallSid);
            if (botSession != null) {
                try {
                    // Try to hangup the bot call
                    Call.updater(botCallSid)
                        .setStatus(Call.UpdateStatus.COMPLETED)
                        .update();
                    logger.info("Bot call {} terminated successfully", botCallSid);
                } catch (Exception e) {
                    logger.warn("Could not terminate bot call {}: {}", botCallSid, e.getMessage());
                }
            }
            
            cleanupBotResources(botCallSid);
            
        } catch (Exception e) {
            logger.error("Error removing bot from conference: {}", e.getMessage(), e);
            cleanupBotResources(botCallSid);
        }
    }

    /**
     * Clean up bot resources and session tracking
     */
    public void cleanupBotResources(String botCallSid) {
        try {
            logger.info("Cleaning up bot resources for call: {}", botCallSid);
            
            BotSession botSession = activeBotSessions.remove(botCallSid);
            if (botSession != null) {
                logger.info("Bot session cleaned up: ConferenceName={}, Duration={}ms",
                           botSession.getConferenceName(), 
                           System.currentTimeMillis() - botSession.getStartTime());
                
                saveSessionMetrics(botSession);
            } else {
                logger.warn("No bot session found for cleanup: {}", botCallSid);
            }
            
        } catch (Exception e) {
            logger.error("Error cleaning up bot resources for {}: {}", botCallSid, e.getMessage(), e);
        }
    }

    /**
     * Handle failed bot calls with simplified retry logic
     */
    public void handleFailedCall(String botCallSid, String errorCode) {
        try {
            logger.error("Bot call {} failed with error code: {}", botCallSid, errorCode);
            
            BotSession botSession = activeBotSessions.get(botCallSid);
            if (botSession == null) {
                logger.warn("No bot session found for failed call: {}", botCallSid);
                return;
            }
            
            // Simple retry logic - only retry on specific errors and limited attempts
            if (shouldRetryFailedCall(errorCode, botSession) && botSession.getRetryCount() < 2) {
                logger.info("Retrying failed bot call for conference: {}", botSession.getConferenceName());
                
                botSession.incrementRetryCount();
                
                // Schedule retry with simple delay
                scheduler.schedule(() -> retryBotCall(botSession), 5, TimeUnit.SECONDS);
            } else {
                logger.error("Bot call failed permanently for conference: {}", botSession.getConferenceName());
                cleanupBotResources(botCallSid);
            }
            
        } catch (Exception e) {
            logger.error("Error handling failed bot call {}: {}", botCallSid, e.getMessage(), e);
        }
    }

    /**
     * Check if conference has active participants
     */
    public boolean hasActiveParticipants(String conferenceName) {
        return activeBotSessions.values().stream()
                .anyMatch(session -> conferenceName.equals(session.getConferenceName()));
    }

    /**
     * Remove all bot participants from a conference
     */
    public void removeAllBotParticipants(String conferenceName) {
        try {
            logger.info("Removing all bot participants from conference: {}", conferenceName);
            
            List<String> botsToRemove = new ArrayList<>();
            for (Map.Entry<String, BotSession> entry : activeBotSessions.entrySet()) {
                if (conferenceName.equals(entry.getValue().getConferenceName())) {
                    botsToRemove.add(entry.getKey());
                }
            }
            
            for (String botCallSid : botsToRemove) {
                removeBotFromConference(botCallSid);
            }
            
            logger.info("Removed {} bots from conference: {}", botsToRemove.size(), conferenceName);
            
        } catch (Exception e) {
            logger.error("Error removing all bots from conference {}: {}", conferenceName, e.getMessage(), e);
        }
    }

    /**
     * Get information about active bot sessions
     */
    public Map<String, BotSession> getActiveBotSessions() {
        return new ConcurrentHashMap<>(activeBotSessions);
    }

    /**
     * Get bot session information for a specific call
     */
    public BotSession getBotSession(String botCallSid) {
        return activeBotSessions.get(botCallSid);
    }

    /**
     * Check if a specific conference has an active bot
     */
    public boolean hasActiveBot(String conferenceName) {
        return activeBotSessions.values().stream()
                .anyMatch(session -> conferenceName.equals(session.getConferenceName()));
    }

    // Private helper methods

    private void scheduleeBotHealthCheck(String botCallSid, int delaySeconds) {
        scheduler.schedule(() -> {
            try {
                BotSession session = activeBotSessions.get(botCallSid);
                if (session != null) {
                    // Simple health check
                    try {
                        Call call = Call.fetcher(botCallSid).fetch();
                        Call.Status status = call.getStatus();
                        
                        logger.info("Bot health check: CallSid={}, Status={}", botCallSid, status);
                        
                        if (status == Call.Status.FAILED || status == Call.Status.CANCELED) {
                            logger.warn("Bot call {} is not healthy, cleaning up", botCallSid);
                            cleanupBotResources(botCallSid);
                        }
                    } catch (Exception e) {
                        logger.warn("Could not fetch bot call status for {}: {}", botCallSid, e.getMessage());
                    }
                }
            } catch (Exception e) {
                logger.error("Error during bot health check for {}: {}", botCallSid, e.getMessage());
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private boolean shouldRetryFailedCall(String errorCode, BotSession botSession) {
        if (errorCode == null) return false;
        
        // Only retry on temporary issues
        switch (errorCode) {
            case "20003": // Internal Twilio error
            case "11206": // HTTP response timeout
                return true;
            default:
                return false;
        }
    }

    private void retryBotCall(BotSession originalSession) {
        try {
            logger.info("Retrying bot call for conference: {}", originalSession.getConferenceName());
            
            activeBotSessions.remove(originalSession.getBotCallSid());
            
            String newBotCallSid = addBotToConference(
                originalSession.getConferenceName(), 
                originalSession.getHumanCallSid()
            );
            
            BotSession newSession = activeBotSessions.get(newBotCallSid);
            if (newSession != null) {
                newSession.setRetryCount(originalSession.getRetryCount());
            }
            
        } catch (Exception e) {
            logger.error("Error retrying bot call for conference {}: {}", 
                        originalSession.getConferenceName(), e.getMessage(), e);
        }
    }

    private void saveSessionMetrics(BotSession botSession) {
        try {
            long duration = System.currentTimeMillis() - botSession.getStartTime();
            logger.info("Bot session metrics - Duration: {}ms, Retries: {}, Conference: {}", 
                       duration, botSession.getRetryCount(), botSession.getConferenceName());
        } catch (Exception e) {
            logger.error("Error saving bot session metrics: {}", e.getMessage(), e);
        }
    }

    private void cleanupStaleBotSessions() {
        try {
            long currentTime = System.currentTimeMillis();
            long maxSessionAge = 60 * 60 * 1000; // 1 hour
            
            activeBotSessions.entrySet().removeIf(entry -> {
                BotSession session = entry.getValue();
                boolean isStale = (currentTime - session.getStartTime()) > maxSessionAge;
                
                if (isStale) {
                    logger.warn("Removing stale bot session: {} (age: {}ms)", 
                               entry.getKey(), currentTime - session.getStartTime());
                }
                
                return isStale;
            });
            
            logger.debug("Cleanup completed. Active bot sessions: {}", activeBotSessions.size());
            
        } catch (Exception e) {
            logger.error("Error during stale bot session cleanup: {}", e.getMessage(), e);
        }
    }

    /**
     * Inner class to track bot session information
     */
    public static class BotSession {
        private final String botCallSid;
        private final String conferenceName;
        private final String humanCallSid;
        private final long startTime;
        private int retryCount = 0;

        public BotSession(String botCallSid, String conferenceName, String humanCallSid, long startTime) {
            this.botCallSid = botCallSid;
            this.conferenceName = conferenceName;
            this.humanCallSid = humanCallSid;
            this.startTime = startTime;
        }

        // Getters and setters
        public String getBotCallSid() { return botCallSid; }
        public String getConferenceName() { return conferenceName; }
        public String getHumanCallSid() { return humanCallSid; }
        public long getStartTime() { return startTime; }
        public int getRetryCount() { return retryCount; }
        public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
        public void incrementRetryCount() { this.retryCount++; }

        @Override
        public String toString() {
            return String.format("BotSession{botCallSid='%s', conferenceName='%s', humanCallSid='%s', startTime=%d, retryCount=%d}",
                    botCallSid, conferenceName, humanCallSid, startTime, retryCount);
        }
    }
}