package com.example.translationcallapp.service;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.rest.api.v2010.account.conference.Participant;
import com.twilio.rest.api.v2010.account.conference.ParticipantUpdater;
import com.twilio.type.PhoneNumber;
import com.twilio.base.ResourceSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.StreamSupport;

@Slf4j
@Service
public class BotParticipantService {

    @Value("${twilio.account.sid}")
    private String twilioAccountSid;

    @Value("${twilio.auth.token}")
    private String twilioAuthToken;

    @Value("${twilio.phone.number}")
    private String twilioPhoneNumber;

    @Value("${app.base.url}")
    private String baseUrl;

    private static final String BOT_ENDPOINT_URL = "/webhook/bot";

    private void initializeTwilio() {
        if (twilioAccountSid != null && twilioAuthToken != null) {
            Twilio.init(twilioAccountSid, twilioAuthToken);
        }
    }

    /**
     * Add bot to conference - 2 parameter version (matches CallController line 79)
     */
    public void addBot(String conferenceSid, String targetLanguage) {
        log.info("Adding bot to conference {} with target language {}", conferenceSid, targetLanguage);
        
        try {
            initializeTwilio();
            
            String botWebhookUrl = baseUrl + BOT_ENDPOINT_URL + 
                "?conferenceSid=" + conferenceSid + 
                "&targetLanguage=" + targetLanguage;

            log.info("Creating bot call with webhook URL: {}", botWebhookUrl);

            // Fixed: Use setUrl() method to properly set the webhook URL
            Call call = Call.creator(
                new PhoneNumber(twilioPhoneNumber), // To (bot's "phone")
                new PhoneNumber(twilioPhoneNumber)  // From
            ).setUrl(URI.create(botWebhookUrl))     // Webhook URL
             .create();

            log.info("Created bot call {} for conference {}", call.getSid(), conferenceSid);

        } catch (Exception e) {
            log.error("Failed to add bot to conference {}: {}", conferenceSid, e.getMessage(), e);
            throw new RuntimeException("Failed to add translation bot", e);
        }
    }

    /**
     * Add translation bot - 2 parameter version (matches CallController line 79)
     */
    public void addTranslationBot(String conferenceSid, String targetLanguage) {
        addBot(conferenceSid, targetLanguage);
    }

    /**
     * Add translation bot - 3 parameter version (matches CallController line 179)  
     */
    public void addTranslationBot(String conferenceSid, String targetLanguage, String sourceLanguage) {
        log.info("Adding translation bot for conference {} - source: {}, target: {}", 
            conferenceSid, sourceLanguage, targetLanguage);
        
        try {
            initializeTwilio();
            
            String botWebhookUrl = baseUrl + BOT_ENDPOINT_URL + 
                "?conferenceSid=" + conferenceSid + 
                "&targetLanguage=" + targetLanguage +
                "&sourceLanguage=" + sourceLanguage;

            log.info("Creating bot call with webhook URL: {}", botWebhookUrl);

            // Fixed: Use setUrl() method to properly set the webhook URL
            Call call = Call.creator(
                new PhoneNumber(twilioPhoneNumber), // To (bot's "phone")
                new PhoneNumber(twilioPhoneNumber)  // From
            ).setUrl(URI.create(botWebhookUrl))     // Webhook URL
             .create();

            log.info("Created bot call {} for conference {} with source {} and target {}", 
                call.getSid(), conferenceSid, sourceLanguage, targetLanguage);

        } catch (Exception e) {
            log.error("Failed to add bot to conference {}: {}", conferenceSid, e.getMessage(), e);
            throw new RuntimeException("Failed to add translation bot", e);
        }
    }

    /**
     * Remove bot by call SID
     */
    public void removeBotByCallSid(String callSid) {
        log.info("Removing bot with call SID: {}", callSid);
        
        try {
            initializeTwilio();
            
            // Update the call to completed status to hang up
            Call call = Call.updater(callSid)
                .setStatus(Call.UpdateStatus.COMPLETED)
                .update();
                
            log.info("Successfully removed bot call: {}", callSid);
            
        } catch (Exception e) {
            log.error("Failed to remove bot call {}: {}", callSid, e.getMessage(), e);
        }
    }

    /**
     * Remove bot - single parameter version (matches CallController line 113)
     */
    public void removeBot(String conferenceSid) {
        removeAllBots(conferenceSid);
    }

    /**
     * Remove all bots from a conference
     */
    public void removeAllBots(String conferenceSid) {
        log.info("Removing all bots from conference: {}", conferenceSid);
        
        try {
            initializeTwilio();
            
            // Get all participants in the conference
            ResourceSet<Participant> participantSet = Participant.reader(conferenceSid).read();
            
            // Convert ResourceSet to List properly
            List<Participant> participants = StreamSupport
                .stream(participantSet.spliterator(), false)
                .collect(ArrayList::new, (list, item) -> list.add(item), ArrayList::addAll);

            // Remove participants that are bots (you may need to adjust this logic)
            for (Participant participant : participants) {
                String callSid = participant.getCallSid();
                
                // Check if this is a bot call (you may need to implement better bot detection logic)
                if (isBotCall(callSid)) {
                    try {
                        // Use the deleter pattern for removing participants
                        Participant.deleter(conferenceSid, callSid).delete();
                        log.info("Removed bot participant with call SID: {}", callSid);
                    } catch (Exception e) {
                        log.error("Failed to remove bot participant {}: {}", callSid, e.getMessage());
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("Failed to remove bots from conference {}: {}", conferenceSid, e.getMessage(), e);
        }
    }

    /**
     * Get all participants in a conference
     */
    public List<Participant> getParticipants(String conferenceSid) {
        try {
            initializeTwilio();
            
            ResourceSet<Participant> participantSet = Participant.reader(conferenceSid).read();
            
            // Convert ResourceSet to List properly
            return StreamSupport
                .stream(participantSet.spliterator(), false)
                .collect(ArrayList::new, (list, item) -> list.add(item), ArrayList::addAll);
                
        } catch (Exception e) {
            log.error("Failed to get participants for conference {}: {}", conferenceSid, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * Mute a participant
     */
    public void muteParticipant(String conferenceSid, String callSid) {
        try {
            initializeTwilio();
            
            ParticipantUpdater updater = Participant.updater(conferenceSid, callSid);
            updater.setMuted(true);
            updater.update();
            
            log.info("Muted participant {} in conference {}", callSid, conferenceSid);
            
        } catch (Exception e) {
            log.error("Failed to mute participant {} in conference {}: {}", 
                callSid, conferenceSid, e.getMessage(), e);
        }
    }

    /**
     * Unmute a participant
     */
    public void unmuteParticipant(String conferenceSid, String callSid) {
        try {
            initializeTwilio();
            
            ParticipantUpdater updater = Participant.updater(conferenceSid, callSid);
            updater.setMuted(false);
            updater.update();
            
            log.info("Unmuted participant {} in conference {}", callSid, conferenceSid);
            
        } catch (Exception e) {
            log.error("Failed to unmute participant {} in conference {}: {}", 
                callSid, conferenceSid, e.getMessage(), e);
        }
    }

    /**
     * Check if a call is a bot call
     * This is a simple implementation - you may want to enhance this logic
     */
    private boolean isBotCall(String callSid) {
        try {
            initializeTwilio();
            Call call = Call.fetcher(callSid).fetch();
            
            // Simple check: if both to and from are the same (bot phone number), it's likely a bot
            return twilioPhoneNumber.equals(call.getTo()) && 
                   twilioPhoneNumber.equals(call.getFrom());
                   
        } catch (Exception e) {
            log.error("Failed to check if call {} is a bot: {}", callSid, e.getMessage());
            return false;
        }
    }

    /**
     * Get the number of participants in a conference
     */
    public int getParticipantCount(String conferenceSid) {
        return getParticipants(conferenceSid).size();
    }

    /**
     * Check if conference has any bots
     */
    public boolean hasBot(String conferenceSid) {
        List<Participant> participants = getParticipants(conferenceSid);
        return participants.stream()
            .anyMatch(p -> isBotCall(p.getCallSid()));
    }
}