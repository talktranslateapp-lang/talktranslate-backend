package com.example.translationcallapp.controller;

import com.example.translationcallapp.service.BotParticipantService;
import com.example.translationcallapp.service.SecureConferenceService;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Conference;
import com.twilio.twiml.voice.Start;
import com.twilio.twiml.voice.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@RestController
@RequestMapping("/webhook")
public class BotWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(BotWebhookController.class);

    @Value("${app.websocket.stream-url}")
    private String websocketStreamUrl;

    @Autowired
    private SecureConferenceService secureConferenceService;

    @Autowired
    private BotParticipantService botParticipantService; // Fixed: Added missing injection

    /**
     * Webhook endpoint for bot participants
     * Returns TwiML to join the bot to a conference and optionally start media streaming
     */
    @RequestMapping(value = "/bot", method = {RequestMethod.GET, RequestMethod.POST}, 
            produces = MediaType.APPLICATION_XML_VALUE)
    public String botWebhook(@RequestParam(required = false) String conferenceName,
                           @RequestParam(required = false) String targetLanguage,
                           @RequestParam(required = false) String sourceLanguage,
                           @RequestParam(required = false) String token,
                           HttpServletResponse response) {
        
        logger.info("Bot webhook called - Conference: {}, Target: {}, Source: {}", 
                   conferenceName, targetLanguage, sourceLanguage);

        try {
            // Validate security token if provided
            if (token != null && !validateWebhookToken(conferenceName, token)) {
                logger.warn("Invalid webhook token for conference: {}", conferenceName);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return buildErrorResponse("Invalid token");
            }

            // Validate conference name format
            if (conferenceName != null && !secureConferenceService.isValidConferenceName(conferenceName)) {
                logger.warn("Invalid conference name format: {}", conferenceName);
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return buildErrorResponse("Invalid conference name");
            }

            // Build TwiML response
            VoiceResponse.Builder responseBuilder = new VoiceResponse.Builder();

            if (conferenceName != null) {
                // Fixed: Remove unsupported statusCallbackEvent method call
                Conference conference = new Conference.Builder(conferenceName)
                        .muted(false)
                        .startConferenceOnEnter(false)
                        .endConferenceOnExit(false)
                        .waitUrl("http://twimlets.com/holdmusic?Bucket=com.twilio.music.classical")
                        .build();

                responseBuilder.conference(conference);

                // Add media streaming if language parameters are provided
                if (targetLanguage != null && sourceLanguage != null) {
                    String streamUrl = buildSecureStreamUrl(conferenceName, targetLanguage, sourceLanguage);
                    
                    Stream stream = new Stream.Builder()
                            .url(streamUrl)
                            .name("translation-stream")
                            .build();

                    Start start = new Start.Builder()
                            .stream(stream) // Fixed: Use stream() method on Start.Builder
                            .build();

                    responseBuilder.start(start);
                }
            } else {
                // Default response when no conference specified
                responseBuilder.say("Bot participant ready");
            }

            VoiceResponse voiceResponse = responseBuilder.build();
            String twimlResponse = voiceResponse.toXml();
            
            logger.debug("Generated TwiML response: {}", twimlResponse);
            return twimlResponse;

        } catch (Exception e) {
            logger.error("Error processing bot webhook: {}", e.getMessage(), e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return buildErrorResponse("Internal server error");
        }
    }

    /**
     * Status callback endpoint for bot calls
     */
    @RequestMapping(value = "/bot/status", method = RequestMethod.POST)
    public void botStatusCallback(@RequestParam String CallSid,
                                @RequestParam String CallStatus,
                                @RequestParam(required = false) String ConferenceSid,
                                @RequestParam(required = false) String From,
                                @RequestParam(required = false) String To) {
        
        logger.info("Bot status callback - Call SID: {}, Status: {}, Conference: {}", 
                   CallSid, CallStatus, ConferenceSid);

        try {
            switch (CallStatus.toLowerCase()) {
                case "answered":
                    logger.info("Bot call {} answered and joined conference {}", CallSid, ConferenceSid);
                    break;
                case "completed":
                case "failed":
                case "canceled":
                    logger.info("Bot call {} ended with status: {}", CallSid, CallStatus);
                    // Cleanup logic if needed
                    break;
                default:
                    logger.debug("Bot call {} status update: {}", CallSid, CallStatus);
            }
        } catch (Exception e) {
            logger.error("Error processing bot status callback: {}", e.getMessage(), e);
        }
    }

    /**
     * Conference status callback endpoint
     */
    @RequestMapping(value = "/conference/status", method = RequestMethod.POST)
    public void conferenceStatusCallback(@RequestParam String ConferenceSid,
                                       @RequestParam String StatusCallbackEvent,
                                       @RequestParam(required = false) String FriendlyName,
                                       @RequestParam(required = false) String Reason) {
        
        logger.info("Conference status callback - SID: {}, Event: {}, Name: {}", 
                   ConferenceSid, StatusCallbackEvent, FriendlyName);

        try {
            switch (StatusCallbackEvent.toLowerCase()) {
                case "conference-start":
                    logger.info("Conference {} started", ConferenceSid);
                    break;
                case "conference-end":
                    logger.info("Conference {} ended - Reason: {}", ConferenceSid, Reason);
                    // Remove conference from tracking
                    if (FriendlyName != null) {
                        secureConferenceService.removeConference(FriendlyName);
                    }
                    break;
                case "participant-join":
                    logger.info("Participant joined conference {}", ConferenceSid);
                    break;
                case "participant-leave":
                    logger.info("Participant left conference {}", ConferenceSid);
                    // Check if we should remove bot participants when all humans leave
                    checkAndCleanupBotParticipants(ConferenceSid);
                    break;
                default:
                    logger.debug("Conference {} event: {}", ConferenceSid, StatusCallbackEvent);
            }
        } catch (Exception e) {
            logger.error("Error processing conference status callback: {}", e.getMessage(), e);
        }
    }

    /**
     * Participant status callback endpoint
     */
    @RequestMapping(value = "/participant/status", method = RequestMethod.POST)
    public void participantStatusCallback(@RequestParam String ConferenceSid,
                                        @RequestParam String CallSid,
                                        @RequestParam String StatusCallbackEvent,
                                        @RequestParam(required = false) String Muted,
                                        @RequestParam(required = false) String Hold) {
        
        logger.info("Participant status callback - Conference: {}, Call: {}, Event: {}", 
                   ConferenceSid, CallSid, StatusCallbackEvent);

        try {
            // Log participant events for monitoring
            switch (StatusCallbackEvent.toLowerCase()) {
                case "participant-join":
                    logger.info("Participant {} joined conference {}", CallSid, ConferenceSid);
                    break;
                case "participant-leave":
                    logger.info("Participant {} left conference {}", CallSid, ConferenceSid);
                    break;
                case "participant-mute":
                    logger.info("Participant {} muted in conference {}", CallSid, ConferenceSid);
                    break;
                case "participant-unmute":
                    logger.info("Participant {} unmuted in conference {}", CallSid, ConferenceSid);
                    break;
                default:
                    logger.debug("Participant {} event {} in conference {}", 
                               CallSid, StatusCallbackEvent, ConferenceSid);
            }
        } catch (Exception e) {
            logger.error("Error processing participant status callback: {}", e.getMessage(), e);
        }
    }

    /**
     * Helper method to build secure streaming URLs
     */
    private String buildSecureStreamUrl(String conferenceSid, String targetLanguage, String sourceLanguage) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String token = generateStreamToken(conferenceSid, targetLanguage, sourceLanguage, timestamp);
        
        return String.format("%s?conference=%s&target=%s&source=%s&timestamp=%s&token=%s",
                websocketStreamUrl, conferenceSid, targetLanguage, sourceLanguage, timestamp, token);
    }

    /**
     * Generates a secure token for stream authentication
     */
    private String generateStreamToken(String conferenceSid, String targetLanguage, String sourceLanguage, String timestamp) {
        try {
            String data = conferenceSid + ":" + targetLanguage + ":" + sourceLanguage + ":" + timestamp;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data.getBytes());
            
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            logger.error("Error generating stream token", e);
            return "fallback-token";
        }
    }

    /**
     * Validates webhook security tokens
     */
    private boolean validateWebhookToken(String conferenceName, String token) {
        try {
            String expectedToken = generateWebhookToken(conferenceName);
            return expectedToken.equals(token);
        } catch (Exception e) {
            logger.error("Error validating webhook token", e);
            return false;
        }
    }

    /**
     * Generates webhook security tokens
     */
    private String generateWebhookToken(String conferenceName) {
        try {
            String data = conferenceName + ":" + System.currentTimeMillis() / 10000; // 10-second windows
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data.getBytes());
            
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString().substring(0, 16); // Truncate for brevity
        } catch (NoSuchAlgorithmException e) {
            logger.error("Error generating webhook token", e);
            return "fallback-token";
        }
    }

    /**
     * Builds error response TwiML
     */
    private String buildErrorResponse(String message) {
        VoiceResponse response = new VoiceResponse.Builder()
                .say("Error: " + message)
                .build();
        return response.toXml();
    }

    /**
     * Checks if bot participants should be removed when humans leave
     */
    private void checkAndCleanupBotParticipants(String conferenceSid) {
        try {
            // Use the service to check and cleanup bot participants
            if (!botParticipantService.hasActiveParticipants(conferenceSid)) {
                logger.info("No active participants in conference {}, cleaning up bots", conferenceSid);
                botParticipantService.removeAllBotParticipants(conferenceSid);
            }
        } catch (Exception e) {
            logger.error("Error during bot participant cleanup for conference {}: {}", conferenceSid, e.getMessage());
        }
    }
}