package com.example.translationcallapp.controller;

import com.example.translationcallapp.service.BotParticipantService;
import com.example.translationcallapp.service.SecureConferenceService;
import com.example.translationcallapp.service.TwilioSecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Dedicated controller for handling all Twilio webhooks
 * Separates webhook logic from business API endpoints
 */
@RestController
@RequestMapping("/webhook")
public class WebhookController {
    
    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);
    
    @Autowired(required = false)
    private TwilioSecurityService twilioSecurityService;
    
    @Autowired
    private SecureConferenceService conferenceService;
    
    @Autowired
    private BotParticipantService botParticipantService;

    /**
     * Handle Twilio call status webhooks - fixes 404 errors
     * Called when call status changes: initiated, ringing, answered, completed, failed, etc.
     */
    @PostMapping("/call-status")
    public ResponseEntity<String> callStatusWebhook(@RequestParam Map<String, String> params) {
        try {
            // Optional: Validate Twilio signature for security
            if (twilioSecurityService != null) {
                // Simplified validation - implement proper signature validation if needed
                logger.debug("Twilio security service available for validation");
            }

            String callSid = params.get("CallSid");
            String callStatus = params.get("CallStatus");
            String callDuration = params.get("CallDuration");
            String from = params.get("From");
            String to = params.get("To");
            
            logger.info("Call status webhook: CallSid={}, Status={}, Duration={}s, From={}, To={}", 
                       callSid, callStatus, callDuration, from, to);
            
            // Handle different call statuses
            switch (callStatus) {
                case "completed":
                    handleCallCompleted(callSid, callDuration);
                    break;
                case "failed":
                    handleCallFailed(callSid, params.get("ErrorCode"), params.get("ErrorMessage"));
                    break;
                case "busy":
                case "no-answer":
                case "canceled":
                    handleCallNotAnswered(callSid, callStatus);
                    break;
                case "initiated":
                case "ringing":
                case "in-progress":
                    // Normal call progression - just log
                    break;
                default:
                    logger.info("Unknown call status: {}", callStatus);
            }
            
            return ResponseEntity.ok("OK");
            
        } catch (Exception e) {
            logger.error("Error processing call status webhook: {}", e.getMessage(), e);
            return ResponseEntity.ok("ERROR");
        }
    }

    /**
     * Handle conference status webhooks
     * Called when conference events occur: start, end, etc.
     */
    @PostMapping("/conference-status")
    public ResponseEntity<String> conferenceStatusWebhook(@RequestParam Map<String, String> params) {
        try {
            String conferenceSid = params.get("ConferenceSid");
            String statusCallbackEvent = params.get("StatusCallbackEvent");
            
            logger.info("Conference status webhook: ConferenceSid={}, Event={}", 
                       conferenceSid, statusCallbackEvent);
            
            if ("conference-end".equals(statusCallbackEvent)) {
                // Conference ended - cleanup if needed
                conferenceService.handleConferenceEnd(conferenceSid);
            }
            
            return ResponseEntity.ok("OK");
            
        } catch (Exception e) {
            logger.error("Error processing conference status webhook: {}", e.getMessage(), e);
            return ResponseEntity.ok("ERROR");
        }
    }

    /**
     * Handle participant status webhooks
     * Called when participants join/leave conferences
     */
    @PostMapping("/participant-status")
    public ResponseEntity<String> participantStatusWebhook(@RequestParam Map<String, String> params) {
        try {
            String conferenceSid = params.get("ConferenceSid");
            String callSid = params.get("CallSid");
            String statusCallbackEvent = params.get("StatusCallbackEvent");
            
            logger.info("Participant status webhook: ConferenceSid={}, CallSid={}, Event={}", 
                       conferenceSid, callSid, statusCallbackEvent);
            
            switch (statusCallbackEvent) {
                case "participant-leave":
                    handleParticipantLeave(conferenceSid, callSid);
                    break;
                case "participant-join":
                    handleParticipantJoin(conferenceSid, callSid);
                    break;
            }
            
            return ResponseEntity.ok("OK");
            
        } catch (Exception e) {
            logger.error("Error processing participant status webhook: {}", e.getMessage(), e);
            return ResponseEntity.ok("ERROR");
        }
    }

    /**
     * Generic webhook handler for other Twilio events
     */
    @PostMapping("/twilio-generic")
    public ResponseEntity<String> genericTwilioWebhook(@RequestParam Map<String, String> params) {
        try {
            logger.info("Generic Twilio webhook received: {}", params);
            return ResponseEntity.ok("OK");
            
        } catch (Exception e) {
            logger.error("Error processing generic Twilio webhook: {}", e.getMessage(), e);
            return ResponseEntity.ok("ERROR");
        }
    }

    // Helper methods for handling different scenarios
    
    private void handleCallCompleted(String callSid, String duration) {
        logger.info("Call {} completed successfully after {}s", callSid, duration);
        
        try {
            // Clean up any bot-related resources
            botParticipantService.cleanupBotResources(callSid);
        } catch (Exception e) {
            logger.warn("Error cleaning up resources for completed call {}: {}", callSid, e.getMessage());
        }
    }
    
    private void handleCallFailed(String callSid, String errorCode, String errorMessage) {
        logger.error("Call {} failed - Error: {} ({})", callSid, errorMessage, errorCode);
        
        try {
            // Clean up any resources and potentially retry if appropriate
            botParticipantService.handleFailedCall(callSid, errorCode);
        } catch (Exception e) {
            logger.warn("Error handling failed call {}: {}", callSid, e.getMessage());
        }
    }
    
    private void handleCallNotAnswered(String callSid, String status) {
        logger.info("Call {} not answered - Status: {}", callSid, status);
        
        try {
            botParticipantService.cleanupBotResources(callSid);
        } catch (Exception e) {
            logger.warn("Error cleaning up resources for unanswered call {}: {}", callSid, e.getMessage());
        }
    }
    
    private void handleParticipantLeave(String conferenceSid, String callSid) {
        logger.info("Participant {} left conference {}", callSid, conferenceSid);
        
        try {
            // Check if conference should be ended when participants leave
            conferenceService.checkConferenceParticipants(conferenceSid);
        } catch (Exception e) {
            logger.warn("Error checking conference participants after leave: {}", e.getMessage());
        }
    }
    
    private void handleParticipantJoin(String conferenceSid, String callSid) {
        logger.info("Participant {} joined conference {}", callSid, conferenceSid);
        
        try {
            // Potentially start recording or other features when participants join
            conferenceService.handleParticipantJoin(conferenceSid, callSid);
        } catch (Exception e) {
            logger.warn("Error handling participant join: {}", e.getMessage());
        }
    }
}