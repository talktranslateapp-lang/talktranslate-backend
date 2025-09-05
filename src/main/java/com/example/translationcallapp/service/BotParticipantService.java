package com.example.translationcallapp.service;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.rest.api.v2010.account.conference.Participant;
import com.twilio.rest.api.v2010.account.conference.ParticipantUpdater;
import com.twilio.type.PhoneNumber;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Conference;
import com.twilio.twiml.voice.Dial;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class BotParticipantService {

    @Value("${twilio.account.sid}")
    private String accountSid;

    @Value("${twilio.auth.token}")
    private String authToken;

    @Value("${twilio.phone.number}")
    private String twilioPhoneNumber;

    @Value("${app.base.url}")
    private String baseUrl;

    private final Map<String, BotParticipant> activeBots = new ConcurrentHashMap<>();
    private final ScheduledExecutorService monitoringExecutor = Executors.newSingleThreadScheduledExecutor();

    public BotParticipantService() {
        // Initialize Twilio in constructor
        log.info("Initializing BotParticipantService");
        // Twilio.init will be called when accountSid and authToken are injected
        log.info("BotParticipantService initialized");
    }

    public String addBotToConference(String conferenceId, String botType, Map<String, String> botConfig) {
        try {
            log.info("Adding bot to conference: {} with type: {}", conferenceId, botType);

            // Initialize Twilio if not already done
            if (!Twilio.isInitialized()) {
                Twilio.init(accountSid, authToken);
            }

            // Generate TwiML for bot behavior
            String twimlUrl = generateBotTwiML(conferenceId, botType, botConfig);

            // Create call to add bot to conference
            Call call = Call.creator(
                    new PhoneNumber(twilioPhoneNumber), // To (bot number)
                    new PhoneNumber(twilioPhoneNumber), // From (same number)
                    URI.create(twimlUrl)
            ).create();

            String botId = call.getSid();
            log.info("Bot call created with SID: {}", botId);

            // Track the bot
            BotParticipant bot = new BotParticipant(botId, conferenceId, botType, botConfig);
            activeBots.put(botId, bot);

            // Start monitoring the bot
            monitorBot(botId);

            return botId;

        } catch (Exception e) {
            log.error("Failed to add bot to conference {}: {}", conferenceId, e.getMessage(), e);
            throw new RuntimeException("Failed to add bot to conference", e);
        }
    }

    public boolean removeBotFromConference(String botId) {
        try {
            log.info("Removing bot from conference: {}", botId);

            BotParticipant bot = activeBots.get(botId);
            if (bot == null) {
                log.warn("Bot not found: {}", botId);
                return false;
            }

            // Initialize Twilio if not already done
            if (!Twilio.isInitialized()) {
                Twilio.init(accountSid, authToken);
            }

            // Find and remove the participant from the conference
            try {
                List<Participant> participants = Participant.reader(bot.getConferenceId()).read();
                for (Participant participant : participants) {
                    if (botId.equals(participant.getCallSid())) {
                        log.info("Found bot participant, removing from conference");
                        participant.delete();
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("Could not find participant in conference, attempting to hang up call directly: {}", e.getMessage());
                
                // Try to hang up the call directly
                try {
                    Call call = Call.fetcher(botId).fetch();
                    if (call != null && !"completed".equals(call.getStatus().toString())) {
                        Call.updater(botId)
                            .setStatus(Call.UpdateStatus.COMPLETED)
                            .update();
                    }
                } catch (Exception callException) {
                    log.error("Failed to hang up bot call: {}", callException.getMessage());
                }
            }

            // Remove from tracking
            activeBots.remove(botId);
            log.info("Bot removed successfully: {}", botId);
            return true;

        } catch (Exception e) {
            log.error("Error removing bot {}: {}", botId, e.getMessage(), e);
            return false;
        }
    }

    public List<Map<String, Object>> getActiveBotsForConference(String conferenceId) {
        List<Map<String, Object>> bots = new ArrayList<>();
        
        for (BotParticipant bot : activeBots.values()) {
            if (conferenceId.equals(bot.getConferenceId())) {
                Map<String, Object> botInfo = new HashMap<>();
                botInfo.put("botId", bot.getBotId());
                botInfo.put("conferenceId", bot.getConferenceId());
                botInfo.put("botType", bot.getBotType());
                botInfo.put("status", bot.getStatus());
                botInfo.put("createdAt", bot.getCreatedAt().toString());
                botInfo.put("config", bot.getConfig());
                bots.add(botInfo);
            }
        }
        
        return bots;
    }

    public boolean muteBotInConference(String botId, boolean mute) {
        try {
            log.info("Setting mute status for bot {}: {}", botId, mute);

            BotParticipant bot = activeBots.get(botId);
            if (bot == null) {
                log.warn("Bot not found: {}", botId);
                return false;
            }

            // Initialize Twilio if not already done
            if (!Twilio.isInitialized()) {
                Twilio.init(accountSid, authToken);
            }

            // Find the participant and update mute status
            List<Participant> participants = Participant.reader(bot.getConferenceId()).read();
            for (Participant participant : participants) {
                if (botId.equals(participant.getCallSid())) {
                    ParticipantUpdater updater = Participant.updater(bot.getConferenceId(), participant.getSid())
                            .setMuted(mute);
                    updater.update();
                    
                    bot.setMuted(mute);
                    log.info("Bot mute status updated: {} = {}", botId, mute);
                    return true;
                }
            }

            log.warn("Could not find participant for bot: {}", botId);
            return false;

        } catch (Exception e) {
            log.error("Error updating mute status for bot {}: {}", botId, e.getMessage(), e);
            return false;
        }
    }

    public Map<String, Object> getBotStatus(String botId) {
        BotParticipant bot = activeBots.get(botId);
        if (bot == null) {
            return null;
        }

        Map<String, Object> status = new HashMap<>();
        status.put("botId", bot.getBotId());
        status.put("conferenceId", bot.getConferenceId());
        status.put("botType", bot.getBotType());
        status.put("status", bot.getStatus());
        status.put("muted", bot.isMuted());
        status.put("createdAt", bot.getCreatedAt().toString());
        status.put("config", bot.getConfig());

        // Try to get live status from Twilio
        try {
            if (!Twilio.isInitialized()) {
                Twilio.init(accountSid, authToken);
            }

            Call call = Call.fetcher(botId).fetch();
            if (call != null) {
                status.put("callStatus", call.getStatus().toString());
                status.put("duration", call.getDuration());
            }
        } catch (Exception e) {
            log.warn("Could not fetch live status for bot {}: {}", botId, e.getMessage());
        }

        return status;
    }

    private String generateBotTwiML(String conferenceId, String botType, Map<String, String> botConfig) {
        try {
            // Create TwiML response for the bot
            VoiceResponse.Builder responseBuilder = new VoiceResponse.Builder();

            // Configure bot behavior based on type
            Conference.Builder conferenceBuilder = new Conference.Builder(conferenceId)
                    .startConferenceOnEnter(false)
                    .endConferenceOnExit(false);

            // Configure based on bot type
            switch (botType.toLowerCase()) {
                case "translator":
                    conferenceBuilder
                            .record(Conference.Record.RECORD_FROM_START)
                            .recordingStatusCallback(URI.create(baseUrl + "/api/recording/status"))
                            .statusCallback(URI.create(baseUrl + "/api/conference/status"));
                    break;
                
                case "transcriber":
                    conferenceBuilder
                            .record(Conference.Record.RECORD_FROM_START)
                            .recordingStatusCallback(URI.create(baseUrl + "/api/recording/transcribe"));
                    break;
                
                case "monitor":
                    conferenceBuilder
                            .muted(true)
                            .beep(false);
                    break;
                
                default:
                    log.warn("Unknown bot type: {}, using default configuration", botType);
            }

            Dial dial = new Dial.Builder()
                    .conference(conferenceBuilder.build())
                    .build();

            VoiceResponse response = responseBuilder.dial(dial).build();
            String twiml = response.toXml();

            log.debug("Generated TwiML for bot: {}", twiml);
            return baseUrl + "/api/bot/twiml?response=" + 
                   java.net.URLEncoder.encode(twiml, "UTF-8");

        } catch (Exception e) {
            log.error("Error generating TwiML for bot: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate TwiML", e);
        }
    }

    private void monitorBot(String botId) {
        monitoringExecutor.schedule(() -> {
            try {
                BotParticipant bot = activeBots.get(botId);
                if (bot == null) {
                    return;
                }

                if (!Twilio.isInitialized()) {
                    Twilio.init(accountSid, authToken);
                }

                // Check if call is still active
                Call call = Call.fetcher(botId).fetch();
                if (call != null) {
                    String status = call.getStatus().toString();
                    bot.setStatus(status);
                    
                    if ("completed".equals(status) || "failed".equals(status) || "canceled".equals(status)) {
                        log.info("Bot call ended: {} with status: {}", botId, status);
                        activeBots.remove(botId);
                    } else {
                        // Continue monitoring
                        monitorBot(botId);
                    }
                }
            } catch (Exception e) {
                log.error("Error monitoring bot {}: {}", botId, e.getMessage(), e);
                // Remove from tracking if there's an error
                activeBots.remove(botId);
            }
        }, 30, TimeUnit.SECONDS);
    }

    public void removeAllBotsFromConference(String conferenceId) {
        log.info("Removing all bots from conference: {}", conferenceId);
        
        List<String> botsToRemove = activeBots.values().stream()
                .filter(bot -> conferenceId.equals(bot.getConferenceId()))
                .map(BotParticipant::getBotId)
                .toList();
        
        for (String botId : botsToRemove) {
            removeBotFromConference(botId);
        }
        
        log.info("Removed {} bots from conference: {}", botsToRemove.size(), conferenceId);
    }

    public int getActiveBotCount() {
        return activeBots.size();
    }

    public Map<String, Object> getServiceStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalActiveBots", activeBots.size());
        
        Map<String, Long> botsByType = activeBots.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                    BotParticipant::getBotType,
                    java.util.stream.Collectors.counting()
                ));
        stats.put("botsByType", botsByType);
        
        Map<String, Long> botsByConference = activeBots.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                    BotParticipant::getConferenceId,
                    java.util.stream.Collectors.counting()
                ));
        stats.put("botsByConference", botsByConference);
        
        return stats;
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up BotParticipantService");
        
        // Remove all active bots
        List<String> botIds = new ArrayList<>(activeBots.keySet());
        for (String botId : botIds) {
            try {
                removeBotFromConference(botId);
            } catch (Exception e) {
                log.error("Error removing bot during cleanup: {}", e.getMessage());
            }
        }
        
        // Shutdown monitoring executor
        monitoringExecutor.shutdown();
        try {
            if (!monitoringExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                monitoringExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            monitoringExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        log.info("BotParticipantService cleanup completed");
    }

    // Inner class to track bot participants
    private static class BotParticipant {
        private final String botId;
        private final String conferenceId;
        private final String botType;
        private final Map<String, String> config;
        private final java.time.LocalDateTime createdAt;
        private String status;
        private boolean muted;

        public BotParticipant(String botId, String conferenceId, String botType, Map<String, String> config) {
            this.botId = botId;
            this.conferenceId = conferenceId;
            this.botType = botType;
            this.config = config != null ? new HashMap<>(config) : new HashMap<>();
            this.createdAt = java.time.LocalDateTime.now();
            this.status = "connecting";
            this.muted = false;
        }

        // Getters and setters
        public String getBotId() { return botId; }
        public String getConferenceId() { return conferenceId; }
        public String getBotType() { return botType; }
        public Map<String, String> getConfig() { return config; }
        public java.time.LocalDateTime getCreatedAt() { return createdAt; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public boolean isMuted() { return muted; }
        public void setMuted(boolean muted) { this.muted = muted; }
    }
}