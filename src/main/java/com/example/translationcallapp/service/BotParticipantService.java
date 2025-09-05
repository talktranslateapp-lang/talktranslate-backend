package com.example.translationcallapp.service;

import com.twilio.rest.api.v2010.account.conference.Participant;
import com.twilio.rest.api.v2010.account.conference.ParticipantUpdater;
import com.twilio.type.PhoneNumber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class BotParticipantService {

    @Value("${twilio.account.sid}")
    private String twilioAccountSid;

    @Value("${twilio.twiml.app.sid}")
    private String twimlAppSid;

    @Value("${bot.participant.lookup.retries:3}")
    private int maxLookupRetries;

    @Value("${bot.participant.thread.pool.size:4}")
    private int threadPoolSize;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(threadPoolSize);
    
    // Track bot participants by conference name
    private final ConcurrentHashMap<String, BotParticipant> botParticipants = new ConcurrentHashMap<>();
    
    // Statistics for monitoring
    private final AtomicInteger totalBotsCreated = new AtomicInteger(0);
    private final AtomicInteger totalPlaybackAttempts = new AtomicInteger(0);
    private final AtomicInteger failedPlaybacks = new AtomicInteger(0);

    public boolean addBotToConference(String conferenceName) {
        try {
            log.info("Adding bot participant to conference: {}", conferenceName);

            if (botParticipants.containsKey(conferenceName)) {
                log.warn("Bot already exists for conference: {}", conferenceName);
                return true;
            }

            com.twilio.rest.api.v2010.account.Call call = com.twilio.rest.api.v2010.account.Call.creator(
                    new PhoneNumber("client:translation-bot-" + conferenceName),
                    new PhoneNumber("client:conference-system"),
                    com.twilio.type.Twiml.fromText(generateBotTwiML(conferenceName))
            )
            .setMachineDetection("Enable")
            .setTimeout(30)
            .create();

            String callSid = call.getSid();
            totalBotsCreated.incrementAndGet();
            
            log.info("Bot call created with SID: {} for conference: {}", callSid, conferenceName);

            BotParticipant bot = new BotParticipant(callSid, null, conferenceName);
            botParticipants.put(conferenceName, bot);

            scheduleParticipantSidLookup(conferenceName, callSid, 0);

            return true;

        } catch (Exception e) {
            log.error("Failed to add bot to conference: {}", conferenceName, e);
            return false;
        }
    }

    private void scheduleParticipantSidLookup(String conferenceName, String callSid, int attempt) {
        int delaySeconds = Math.min(2 * (1 << attempt), 8);
        
        scheduler.schedule(() -> {
            try {
                String participantSid = findBotParticipantSid(conferenceName, callSid);
                
                if (participantSid != null) {
                    BotParticipant bot = botParticipants.get(conferenceName);
                    if (bot != null) {
                        bot.setParticipantSid(participantSid);
                        bot.setLookupComplete(true);
                        log.info("Bot participant SID found - Conference: {}, ParticipantSID: {}, Attempts: {}", 
                                conferenceName, participantSid, attempt + 1);
                    }
                } else if (attempt < maxLookupRetries - 1) {
                    log.warn("Bot participant SID not found for conference: {}, retrying... (attempt {}/{})", 
                            conferenceName, attempt + 1, maxLookupRetries);
                    scheduleParticipantSidLookup(conferenceName, callSid, attempt + 1);
                } else {
                    log.error("Failed to find bot participant SID for conference: {} after {} attempts", 
                             conferenceName, maxLookupRetries);
                    BotParticipant bot = botParticipants.get(conferenceName);
                    if (bot != null) {
                        bot.setLookupFailed(true);
                    }
                }
                
            } catch (Exception e) {
                log.error("Error during participant SID lookup for conference: {}, attempt: {}", 
                         conferenceName, attempt + 1, e);
                
                if (attempt < maxLookupRetries - 1) {
                    scheduleParticipantSidLookup(conferenceName, callSid, attempt + 1);
                }
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    public boolean playTranslatedAudio(String conferenceName, String audioUrl) {
        try {
            totalPlaybackAttempts.incrementAndGet();
            
            BotParticipant bot = botParticipants.get(conferenceName);
            if (bot == null) {
                log.warn("No bot participant found for conference: {}", conferenceName);
                failedPlaybacks.incrementAndGet();
                return false;
            }

            if (bot.getParticipantSid() == null) {
                if (bot.isLookupFailed()) {
                    log.error("Bot participant SID lookup failed for conference: {}", conferenceName);
                } else {
                    log.warn("Bot participant SID not yet available for conference: {} (lookup in progress)", 
                            conferenceName);
                }
                failedPlaybacks.incrementAndGet();
                return false;
            }

            log.debug("Playing translated audio in conference: {} via bot: {}, URL: {}", 
                     conferenceName, bot.getParticipantSid(), audioUrl);

            ParticipantUpdater updater = Participant.updater(conferenceName, bot.getParticipantSid())
                    .setAnnounceUrl(audioUrl)
                    .setAnnounceMethod(com.twilio.http.HttpMethod.GET);

            Participant participant = updater.update();
            bot.incrementPlaybackCount();
            
            log.debug("Audio playback initiated for bot participant: {}, total playbacks: {}", 
                     participant.getSid(), bot.getPlaybackCount());
            
            return true;

        } catch (Exception e) {
            log.error("Failed to play translated audio in conference: {}, URL: {}", conferenceName, audioUrl, e);
            failedPlaybacks.incrementAndGet();
            return false;
        }
    }

    public boolean removeBotFromConference(String conferenceName) {
        try {
            BotParticipant bot = botParticipants.remove(conferenceName);
            if (bot == null) {
                log.warn("No bot participant to remove for conference: {}", conferenceName);
                return false;
            }

            log.info("Removing bot participant from conference: {} (played {} audio files)", 
                    conferenceName, bot.getPlaybackCount());

            if (bot.getCallSid() != null) {
                com.twilio.rest.api.v2010.account.Call.updater(bot.getCallSid())
                        .setStatus(com.twilio.rest.api.v2010.account.Call.UpdateStatus.COMPLETED)
                        .update();
                log.info("Bot call terminated: {}", bot.getCallSid());
            }

            return true;

        } catch (Exception e) {
            log.error("Error removing bot from conference: {}", conferenceName, e);
            return false;
        }
    }

    public boolean isBotActive(String conferenceName) {
        BotParticipant bot = botParticipants.get(conferenceName);
        return bot != null && bot.getParticipantSid() != null && !bot.isLookupFailed();
    }

    public int getActiveBotCount() {
        return botParticipants.size();
    }

    private String findBotParticipantSid(String conferenceName, String callSid) {
        try {
            Iterable<Participant> participants = Participant.reader(conferenceName).read();
            
            for (Participant participant : participants) {
                if (callSid.equals(participant.getCallSid())) {
                    return participant.getSid();
                }
            }
            
            return null;
            
        } catch (Exception e) {
            log.error("Error finding bot participant SID for conference: {}", conferenceName, e);
            return null;
        }
    }

    private String generateBotTwiML(String conferenceName) {
        return String.format(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <Response>
                <Dial>
                    <Conference 
                        startConferenceOnEnter="true" 
                        endConferenceOnExit="false"
                        muted="false"
                        beep="false"
                        waitUrl=""
                        maxParticipants="10">%s</Conference>
                </Dial>
            </Response>
            """, 
            conferenceName
        );
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up Bot Participant Service - Active bots: {}, Total created: {}", 
                botParticipants.size(), totalBotsCreated.get());
        
        botParticipants.keySet().forEach(this::removeBotFromConference);
        botParticipants.clear();
        
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static class BotParticipant {
        private final String callSid;
        private volatile String participantSid;
        private final String conferenceName;
        private volatile boolean lookupComplete = false;
        private volatile boolean lookupFailed = false;
        private final AtomicInteger playbackCount = new AtomicInteger(0);

        public BotParticipant(String callSid, String participantSid, String conferenceName) {
            this.callSid = callSid;
            this.participantSid = participantSid;
            this.conferenceName = conferenceName;
        }

        public String getCallSid() { return callSid; }
        public String getParticipantSid() { return participantSid; }
        public void setParticipantSid(String participantSid) { this.participantSid = participantSid; }
        public String getConferenceName() { return conferenceName; }
        public boolean isLookupComplete() { return lookupComplete; }
        public void setLookupComplete(boolean lookupComplete) { this.lookupComplete = lookupComplete; }
        public boolean isLookupFailed() { return lookupFailed; }
        public void setLookupFailed(boolean lookupFailed) { this.lookupFailed = lookupFailed; }
        public int getPlaybackCount() { return playbackCount.get(); }
        public void incrementPlaybackCount() { playbackCount.incrementAndGet(); }
    }
}