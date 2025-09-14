package com.example.translationcallapp.controller;

import com.example.translationcallapp.service.BotParticipantService;
import com.example.translationcallapp.service.SecureConferenceService;
import com.example.translationcallapp.service.TwilioSecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Handles all webhook callbacks from external services (primarily Twilio)
 * Separated from API controllers for better organization and security
 */
@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);

    @Autowired
    private BotParticipantService botParticipantService;

    @Autowired
    private SecureConferenceService secureConferenceService;

    @Autowired
    private TwilioSecurityService twilioSecurityService;

    /**
     * Handle Twilio call status webhooks
     * This fixes the 404 errors in your logs
     */
    @PostMapping("/call-status")
    public ResponseEntity<String> callStatusWebhook(
            @RequestParam Map<String, String> params,
            HttpServletRequest request) {
        
        try {
            // Extract common parameters
            String callSid = params.get("CallSid");
            String callStatus = params.get("CallStatus");
            String from = params.get("From");
            String to = params.get("To");
            String direction = params.get("Direction");
            String accountSid = params.get("AccountSid");
            
            logger.info("Call status webhook: CallSid={}, Status={}, From={}, To={}, Direction={}", 
                       callSid, callStatus, from, to, direction);

            // Optional: Validate request came from Twilio
            try {
                if (!twilioSecurityService.validateTwilioRequest(request)) {
                    logger.warn("Invalid Twilio request signature for call status webhook");
                    return ResponseEntity.status(403).body("FORBIDDEN");
                }
            } catch (Exception e) {
                logger.debug("Twilio signature validation skipped: {}", e.getMessage());
                // Continue processing - validation might not be configured
            }

            // Handle different call statuses
            if (callStatus != null) {
                switch (callStatus.toLowerCase()) {
                    case "initiated":
                        logger.debug("Call {} initiated", callSid);
                        break;
                        
                    case "ringing":
                        logger.debug("Call {} ringing", callSid);
                        break;
                        
                    case "answered":
                        logger.info("Call {} answered successfully", callSid);
                        break;
                        
                    case "completed":
                        logger.info("Call {} completed successfully", callSid);
                        handleCallCompleted(callSid, params);
                        break;
                        
                    case "failed":
                        logger.warn("Call {} failed. Reason: {}", callSid, params.get("CallStatusReason"));
                        handleCallFailed(callSid, params);
                        break;
                        
                    case "busy":
                        logger.info("Call {} received busy signal", callSid);
                        handleCallBusy(callSid, params);
                        break;
                        
                    case "no-answer":
                        logger.info("Call {} had no answer", callSid);
                        handleCallNoAnswer(callSid, params);
                        break;
                        
                    case "canceled":
                        logger.info("Call {} was canceled", callSid);
                        handleCallCanceled(callSid, params);
                        break;
                        
                    default:
                        logger.debug("Call {} status update: {}", callSid, callStatus);
                }
            }

            // Log additional useful parameters for debugging
            if (params.containsKey("Duration")) {
                logger.debug("Call {} duration: {} seconds", callSid, params.get("Duration"));
            }
            
            if (params.containsKey("CallPrice")) {
                logger.debug("Call {} price: {}", callSid, params.get("CallPrice"));
            }

            return ResponseEntity.ok("OK");
            
        } catch (Exception e) {
            logger.error("Error processing call status webhook: {}", e.getMessage(), e);
            // Return OK even on error to prevent Twilio retries
            return ResponseEntity.ok("ERROR");
        }
    }

    /**
     * Handle conference status webhooks
     */
    @PostMapping("/conference-status")
    public ResponseEntity<String> conferenceStatusWebhook(
            @RequestParam Map<String, String> params,
            HttpServletRequest request) {
        
        try {
            String conferenceSid = params.get("ConferenceSid");
            String statusCallbackEvent = params.get("StatusCallbackEvent");
            String friendlyName = params.get("FriendlyName");
            String reason = params.get("Reason");
            
            logger.info("Conference status webhook: SID={}, Event={}, Name={}, Reason={}", 
                       conferenceSid, statusCallbackEvent, friendlyName, reason);

            // Optional: Validate request came from Twilio
            try {
                if (!twilioSecurityService.validateTwilioRequest(request)) {
                    logger.warn("Invalid Twilio request signature for conference status webhook");
                    return ResponseEntity.status(403).body("FORBIDDEN");
                }
            } catch (Exception e) {
                logger.debug("Twilio signature validation skipped: {}", e.getMessage());
            }

            if (statusCallbackEvent != null) {
                switch (statusCallbackEvent.toLowerCase()) {
                    case "conference-start":
                        logger.info("Conference {} started", conferenceSid);
                        break;
                        
                    case "conference-end":
                        logger.info("Conference {} ended - Reason: {}", conferenceSid, reason);
                        handleConferenceEnd(conferenceSid, friendlyName, reason);
                        break;
                        
                    case "participant-join":
                        logger.info("Participant joined conference {}", conferenceSid);
                        break;
                        
                    case "participant-leave":
                        logger.info("Participant left conference {}", conferenceSid);
                        checkAndCleanupConference(conferenceSid);
                        break;
                        
                    default:
                        logger.debug("Conference {} event: {}", conferenceSid, statusCallbackEvent);
                }
            }

            return ResponseEntity.ok("OK");
            
        } catch (Exception e) {
            logger.error("Error processing conference status webhook: {}", e.getMessage(), e);
            return ResponseEntity.ok("ERROR");
        }
    }

    /**
     * Handle participant status webhooks
     */
    @PostMapping("/participant-status")
    public ResponseEntity<String> participantStatusWebhook(
            @RequestParam Map<String, String> params,
            HttpServletRequest request) {
        
        try {
            String conferenceSid = params.get("ConferenceSid");
            String callSid = params.get("CallSid");
            String statusCallbackEvent = params.get("StatusCallbackEvent");
            String muted = params.get("Muted");
            String hold = params.get("Hold");
            
            logger.info("Participant status webhook: Conference={}, Call={}, Event={}, Muted={}, Hold={}", 
                       conferenceSid, callSid, statusCallbackEvent, muted, hold);

            // Optional: Validate request came from Twilio
            try {
                if (!twilioSecurityService.validateTwilioRequest(request)) {
                    logger.warn("Invalid Twilio request signature for participant status webhook");
                    return ResponseEntity.status(403).body("FORBIDDEN");
                }
            } catch (Exception e) {
                logger.debug("Twilio signature validation skipped: {}", e.getMessage());
            }

            if (statusCallbackEvent != null) {
                switch (statusCallbackEvent.toLowerCase()) {
                    case "participant-join":
                        logger.info("Participant {} joined conference {}", callSid, conferenceSid);
                        break;
                        
                    case "participant-leave":
                        logger.info("Participant {} left conference {}", callSid, conferenceSid);
                        checkAndCleanupConference(conferenceSid);
                        break;
                        
                    case "participant-mute":
                        logger.info("Participant {} muted in conference {}", callSid, conferenceSid);
                        break;
                        
                    case "participant-unmute":
                        logger.info("Participant {} unmuted in conference {}", callSid, conferenceSid);
                        break;
                        
                    default:
                        logger.debug("Participant {} event {} in conference {}", 
                                   callSid, statusCallbackEvent, conferenceSid);
                }
            }

            return ResponseEntity.ok("OK");
            
        } catch (Exception e) {
            logger.error("Error processing participant status webhook: {}", e.getMessage(), e);
            return ResponseEntity.ok("ERROR");
        }
    }

    /**
     * Generic webhook handler for any other Twilio webhooks
     */
    @PostMapping("/twilio-generic")
    public ResponseEntity<String> genericTwilioWebhook(
            @RequestParam Map<String, String> params,
            HttpServletRequest request) {
        
        try {
            logger.info("Generic Twilio webhook received: {}", params);
            
            // Optional: Validate request came from Twilio
            try {
                if (!twilioSecurityService.validateTwilioRequest(request)) {
                    logger.warn("Invalid Twilio request signature for generic webhook");
                    return ResponseEntity.status(403).body("FORBIDDEN");
                }
            } catch (Exception e) {
                logger.debug("Twilio signature validation skipped: {}", e.getMessage());
            }

            return ResponseEntity.ok("OK");
            
        } catch (Exception e) {
            logger.error("Error processing generic Twilio webhook: {}", e.getMessage(), e);
            return ResponseEntity.ok("ERROR");
        }
    }

    // Private helper methods for handling specific events

    private void handleCallCompleted(String callSid, Map<String, String> params) {
        try {
            // Clean up any bot metadata if this was a bot call
            var metadata = botParticipantService.getStoredCallData();
            for (var entry : metadata.entrySet()) {
                if (entry.getValue().getCallSid() != null && 
                    entry.getValue().getCallSid().equals(callSid)) {
                    
                    if (entry.getValue().isBot()) {
                        logger.info("Bot call {} completed - cleaning up metadata", callSid);
                        // The bot participant service will handle cleanup
                    }
                    break;
                }
            }
        } catch (Exception e) {
            logger.error("Error handling call completed for {}: {}", callSid, e.getMessage());
        }
    }

    private void handleCallFailed(String callSid, Map<String, String> params) {
        try {
            String reason = params.get("CallStatusReason");
            logger.warn("Call {} failed with reason: {}", callSid, reason);
            
            // Could implement retry logic here for bot calls
            // Could send notifications about failed participant calls
            
        } catch (Exception e) {
            logger.error("Error handling call failed for {}: {}", callSid, e.getMessage());
        }
    }

    private void handleCallBusy(String callSid, Map<String, String> params) {
        logger.info("Call {} received busy signal - participant may be unavailable", callSid);
        // Could implement retry logic or notifications here
    }

    private void handleCallNoAnswer(String callSid, Map<String, String> params) {
        logger.info("Call {} had no answer - participant may be unavailable", callSid);
        // Could implement retry logic or notifications here
    }

    private void handleCallCanceled(String callSid, Map<String, String> params) {
        logger.info("Call {} was canceled before completion", callSid);
        // Handle any cleanup needed for canceled calls
    }

    private void handleConferenceEnd(String conferenceSid, String friendlyName, String reason) {
        try {
            logger.info("Conference {} ended - performing cleanup", conferenceSid);
            
            // Remove from secure conference tracking
            if (friendlyName != null) {
                secureConferenceService.removeConference(friendlyName);
            }
            
            // The bot participant service should handle its own cleanup
            
        } catch (Exception e) {
            logger.error("Error handling conference end for {}: {}", conferenceSid, e.getMessage());
        }
    }

    private void checkAndCleanupConference(String conferenceSid) {
        try {
            // Check if conference has any remaining participants
            if (!botParticipantService.hasActiveParticipants(conferenceSid)) {
                logger.info("No active participants in conference {} - cleaning up bots", conferenceSid);
                botParticipantService.removeAllBotParticipants(conferenceSid);
            }
        } catch (Exception e) {
            logger.error("Error during conference cleanup for {}: {}", conferenceSid, e.getMessage());
        }
    }
}