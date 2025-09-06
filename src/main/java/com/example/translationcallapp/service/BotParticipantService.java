package com.example.translationcallapp.service;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.rest.api.v2010.account.Conference;
import com.twilio.rest.api.v2010.account.conference.Participant;
import com.twilio.rest.api.v2010.account.conference.ParticipantCreator;
import com.twilio.type.PhoneNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class BotParticipantService {

    private static final Logger logger = LoggerFactory.getLogger(BotParticipantService.class);
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1000;

    @Value("${twilio.account.sid}")
    private String twilioAccountSid;

    @Value("${twilio.auth.token}")
    private String twilioAuthToken;

    @Value("${twilio.phone.number}")
    private String twilioPhoneNumber;

    @Value("${app.base.url}")
    private String baseUrl;

    private volatile boolean twilioInitialized = false;

    public synchronized void initializeTwilio() {
        if (!twilioInitialized) {
            if (twilioAccountSid == null || twilioAuthToken == null) {
                logger.error("Twilio credentials not configured");
                throw new RuntimeException("Twilio credentials not configured");
            }
            Twilio.init(twilioAccountSid, twilioAuthToken);
            twilioInitialized = true;
            logger.info("Twilio initialized successfully");
        }
    }

    /**
     * Add a translation bot to a conference with retry logic
     */
    public String addTranslationBot(String conferenceSid, String sourceLanguage, String targetLanguage) {
        return executeWithRetry(() -> {
            initializeTwilio();
            
            // Generate secure webhook URL with timestamp and hash for security
            String timestamp = String.valueOf(System.currentTimeMillis());
            String secureToken = generateSecureToken(conferenceSid, timestamp);
            
            String botWebhookUrl = String.format("%s/webhook/bot?conferenceSid=%s&targetLanguage=%s&sourceLanguage=%s&timestamp=%s&token=%s",
                baseUrl, conferenceSid, targetLanguage, sourceLanguage, timestamp, secureToken);
            
            logger.info("Adding translation bot for conference {} - source: {}, target: {}", 
                conferenceSid, sourceLanguage, targetLanguage);
            logger.debug("Bot webhook URL: {}", botWebhookUrl);

            // Create the bot call using correct Twilio API
            Call call = Call.creator(
                new PhoneNumber(twilioPhoneNumber), // To
                new PhoneNumber(twilioPhoneNumber), // From  
                URI.create(botWebhookUrl)           // Webhook URL
            )
            .setStatusCallback(URI.create(baseUrl + "/webhook/call-status"))
            .setStatusCallbackEvent("initiated ringing answered completed")
            .setStatusCallbackMethod("POST")
            .create();

            logger.info("Translation bot call created with SID: {}", call.getSid());
            return call.getSid();
        });
    }

    /**
     * Add a simple bot to conference (without translation)
     */
    public String addBot(String conferenceSid, String targetLanguage) {
        return executeWithRetry(() -> {
            initializeTwilio();
            
            String timestamp = String.valueOf(System.currentTimeMillis());
            String secureToken = generateSecureToken(conferenceSid, timestamp);
            
            String botWebhookUrl = String.format("%s/webhook/bot?conferenceSid=%s&targetLanguage=%s&timestamp=%s&token=%s",
                baseUrl, conferenceSid, targetLanguage, timestamp, secureToken);
            
            logger.info("Creating bot call for conference {} with target language {}", conferenceSid, targetLanguage);

            Call call = Call.creator(
                new PhoneNumber(twilioPhoneNumber),
                new PhoneNumber(twilioPhoneNumber),
                URI.create(botWebhookUrl)
            )
            .setStatusCallback(URI.create(baseUrl + "/webhook/call-status"))
            .create();

            logger.info("Bot call created successfully with SID: {}", call.getSid());
            return call.getSid();
        });
    }

    /**
     * Add a regular participant to conference
     */
    public String addParticipantToConference(String conferenceSid, String phoneNumber) {
        return executeWithRetry(() -> {
            initializeTwilio();
            
            logger.info("Adding participant {} to conference {}", phoneNumber, conferenceSid);

            // Create secure webhook URL for participant
            String timestamp = String.valueOf(System.currentTimeMillis());
            String secureToken = generateSecureToken(conferenceSid, timestamp);
            String participantWebhookUrl = String.format("%s/webhook/participant?conferenceSid=%s&timestamp=%s&token=%s",
                baseUrl, conferenceSid, timestamp, secureToken);

            Call call = Call.creator(
                new PhoneNumber(phoneNumber),        // To (participant's phone)
                new PhoneNumber(twilioPhoneNumber),  // From (your Twilio number)
                URI.create(participantWebhookUrl)    // Webhook URL
            )
            .setStatusCallback(URI.create(baseUrl + "/webhook/call-status"))
            .create();

            logger.info("Participant call created with SID: {} for phone {}", call.getSid(), phoneNumber);
            return call.getSid();
        });
    }

    /**
     * Get conference information
     */
    public Conference getConferenceInfo(String conferenceSid) {
        return executeWithRetry(() -> {
            initializeTwilio();
            
            Conference conference = Conference.fetcher(conferenceSid).fetch();
            logger.info("Conference {} status: {}, participants: {}", 
                conferenceSid, conference.getStatus(), conference.getReasonConferenceEnded());
            return conference;
        });
    }

    /**
     * Remove participant from conference
     */
    public void removeParticipantFromConference(String conferenceSid, String participantSid) {
        executeWithRetry(() -> {
            initializeTwilio();
            
            logger.info("Removing participant {} from conference {}", participantSid, conferenceSid);

            // First mute the participant
            Participant.updater(conferenceSid, participantSid)
                .setMuted(true)
                .update();

            // Then remove them
            Participant.deleter(conferenceSid, participantSid).delete();
            logger.info("Participant {} removed from conference {}", participantSid, conferenceSid);
            return null;
        });
    }

    /**
     * Get all participants in a conference
     */
    public List<Participant> getConferenceParticipants(String conferenceSid) {
        return executeWithRetry(() -> {
            initializeTwilio();
            
            logger.info("Getting participants for conference {}", conferenceSid);
            List<Participant> participants = Participant.reader(conferenceSid).read();
            logger.info("Found {} participants in conference {}", participants.size(), conferenceSid);
            
            return participants;
        });
    }

    /**
     * End conference by removing all participants
     */
    public void endConference(String conferenceSid) {
        executeWithRetry(() -> {
            initializeTwilio();
            
            logger.info("Ending conference {}", conferenceSid);
            
            List<Participant> participants = getConferenceParticipants(conferenceSid);
            for (Participant participant : participants) {
                try {
                    Participant.deleter(conferenceSid, participant.getSid()).delete();
                    logger.info("Removed participant {} from conference", participant.getSid());
                } catch (Exception e) {
                    logger.warn("Failed to remove participant {}: {}", participant.getSid(), e.getMessage());
                }
            }
            
            logger.info("Conference {} ended successfully", conferenceSid);
            return null;
        });
    }

    /**
     * Generate a secure token for webhook URLs to prevent unauthorized access
     */
    private String generateSecureToken(String conferenceSid, String timestamp) {
        try {
            String data = conferenceSid + ":" + timestamp + ":" + twilioAuthToken;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data.getBytes("UTF-8"));
            return Base64.getEncoder().encodeToString(hash).substring(0, 16); // First 16 chars
        } catch (Exception e) {
            logger.warn("Failed to generate secure token, using fallback: {}", e.getMessage());
            return "fallback-token";
        }
    }

    /**
     * Validate webhook security token
     */
    public boolean validateWebhookToken(String conferenceSid, String timestamp, String token) {
        try {
            long timestampLong = Long.parseLong(timestamp);
            long currentTime = System.currentTimeMillis();
            
            // Check if timestamp is within 5 minutes (300,000 ms)
            if (Math.abs(currentTime - timestampLong) > 300_000) {
                logger.warn("Webhook token expired for conference {}", conferenceSid);
                return false;
            }
            
            String expectedToken = generateSecureToken(conferenceSid, timestamp);
            boolean isValid = expectedToken.equals(token);
            
            if (!isValid) {
                logger.warn("Invalid webhook token for conference {}", conferenceSid);
            }
            
            return isValid;
        } catch (Exception e) {
            logger.error("Error validating webhook token: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Execute operation with retry logic for handling transient failures
     */
    private <T> T executeWithRetry(java.util.concurrent.Callable<T> operation) {
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                return operation.call();
            } catch (Exception e) {
                lastException = e;
                logger.warn("Attempt {} failed: {}", attempt, e.getMessage());
                
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(RETRY_DELAY_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        logger.error("All {} attempts failed", MAX_RETRY_ATTEMPTS);
        throw new RuntimeException("Operation failed after " + MAX_RETRY_ATTEMPTS + " attempts", lastException);
    }
}