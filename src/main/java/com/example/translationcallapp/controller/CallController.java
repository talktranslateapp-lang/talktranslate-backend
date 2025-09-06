package com.example.translationcallapp.controller;

import com.example.translationcallapp.service.BotParticipantService;
import com.example.translationcallapp.service.SecureConferenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/calls")
public class CallController {

    private static final Logger logger = LoggerFactory.getLogger(CallController.class);

    @Autowired
    private BotParticipantService botParticipantService;

    @Autowired
    private SecureConferenceService secureConferenceService;

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("service", "CallController");
        health.put("status", "UP");
        health.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(health);
    }

    /**
     * Start a new translation call session
     */
    @PostMapping("/start-translation")
    public ResponseEntity<Map<String, Object>> startTranslationCall(
            @RequestBody Map<String, String> request) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            String fromLanguage = request.get("fromLanguage");
            String toLanguage = request.get("toLanguage");
            String phoneNumber = request.get("phoneNumber");

            // Validate input
            if (fromLanguage == null || toLanguage == null) {
                response.put("success", false);
                response.put("error", "Both fromLanguage and toLanguage are required");
                return ResponseEntity.badRequest().body(response);
            }

            // Generate secure internal conference ID
            String internalConferenceId = "translation-" + System.currentTimeMillis();
            
            // Generate secure, non-PII conference name
            String secureConferenceSid = secureConferenceService.generateSecureConferenceName(
                internalConferenceId, fromLanguage, toLanguage);

            logger.info("Starting translation call: {} -> {}, Phone: {}, Conference: {}", 
                       fromLanguage, toLanguage, phoneNumber, secureConferenceSid);

            // Add translation bot to conference
            String botCallSid = botParticipantService.addTranslationBot(
                secureConferenceSid, fromLanguage, toLanguage);

            // If phone number provided, add participant
            String participantCallSid = null;
            if (phoneNumber != null && !phoneNumber.trim().isEmpty()) {
                participantCallSid = botParticipantService.addParticipantToConference(
                    secureConferenceSid, phoneNumber);
            }

            response.put("success", true);
            response.put("conferenceSid", secureConferenceSid);
            response.put("internalId", internalConferenceId);
            response.put("botCallSid", botCallSid);
            if (participantCallSid != null) {
                response.put("participantCallSid", participantCallSid);
            }
            response.put("fromLanguage", fromLanguage);
            response.put("toLanguage", toLanguage);

            logger.info("Translation call started successfully: conference={}, bot={}", 
                       secureConferenceSid, botCallSid);

        } catch (Exception e) {
            logger.error("Failed to start translation call: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("error", "Failed to start translation call: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Add a bot to an existing conference
     */
    @PostMapping("/add-bot")
    public ResponseEntity<Map<String, Object>> addBot(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            String conferenceSid = request.get("conferenceSid");
            String targetLanguage = request.get("targetLanguage");

            if (conferenceSid == null || targetLanguage == null) {
                response.put("success", false);
                response.put("error", "Both conferenceSid and targetLanguage are required");
                return ResponseEntity.badRequest().body(response);
            }

            // Validate conference name format
            if (!secureConferenceService.isValidConferenceName(conferenceSid)) {
                response.put("success", false);
                response.put("error", "Invalid conference identifier");
                return ResponseEntity.badRequest().body(response);
            }

            String botCallSid = botParticipantService.addBot(conferenceSid, targetLanguage);

            response.put("success", true);
            response.put("botCallSid", botCallSid);
            response.put("conferenceSid", conferenceSid);

        } catch (Exception e) {
            logger.error("Failed to add bot: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("error", "Failed to add translation bot: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Add a participant to an existing conference
     */
    @PostMapping("/add-participant")
    public ResponseEntity<Map<String, Object>> addParticipant(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            String conferenceSid = request.get("conferenceSid");
            String phoneNumber = request.get("phoneNumber");

            if (conferenceSid == null || phoneNumber == null) {
                response.put("success", false);
                response.put("error", "Both conferenceSid and phoneNumber are required");
                return ResponseEntity.badRequest().body(response);
            }

            // Validate conference name format
            if (!secureConferenceService.isValidConferenceName(conferenceSid)) {
                response.put("success", false);
                response.put("error", "Invalid conference identifier");
                return ResponseEntity.badRequest().body(response);
            }

            String participantCallSid = botParticipantService.addParticipantToConference(
                conferenceSid, phoneNumber);

            response.put("success", true);
            response.put("participantCallSid", participantCallSid);
            response.put("conferenceSid", conferenceSid);

        } catch (Exception e) {
            logger.error("Failed to add participant: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("error", "Failed to add participant: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Get conference status and participants
     */
    @GetMapping("/conference/{conferenceSid}/status")
    public ResponseEntity<Map<String, Object>> getConferenceStatus(@PathVariable String conferenceSid) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            // Validate conference name format
            if (!secureConferenceService.isValidConferenceName(conferenceSid)) {
                response.put("success", false);
                response.put("error", "Invalid conference identifier");
                return ResponseEntity.badRequest().body(response);
            }

            var participants = botParticipantService.getConferenceParticipants(conferenceSid);
            var conferenceInfo = botParticipantService.getConferenceInfo(conferenceSid);
            var metadata = secureConferenceService.getConferenceMetadata(conferenceSid);

            response.put("success", true);
            response.put("conferenceSid", conferenceSid);
            response.put("participantCount", participants.size());
            response.put("status", conferenceInfo.getStatus());
            
            if (metadata != null) {
                response.put("sourceLanguage", metadata.getSourceLanguage());
                response.put("targetLanguage", metadata.getTargetLanguage());
                response.put("createdAt", metadata.getCreatedAt());
            }

        } catch (Exception e) {
            logger.error("Failed to get conference status: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("error", "Failed to get conference status: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * End a conference
     */
    @PostMapping("/conference/{conferenceSid}/end")
    public ResponseEntity<Map<String, Object>> endConference(@PathVariable String conferenceSid) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            // Validate conference name format
            if (!secureConferenceService.isValidConferenceName(conferenceSid)) {
                response.put("success", false);
                response.put("error", "Invalid conference identifier");
                return ResponseEntity.badRequest().body(response);
            }

            botParticipantService.endConference(conferenceSid);

            response.put("success", true);
            response.put("message", "Conference ended successfully");

        } catch (Exception e) {
            logger.error("Failed to end conference: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("error", "Failed to end conference: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Cleanup expired conferences (can be called periodically)
     */
    @PostMapping("/cleanup")
    public ResponseEntity<Map<String, Object>> cleanupExpiredConferences() {
        Map<String, Object> response = new HashMap<>();
        
        try {
            secureConferenceService.cleanupExpiredConferences();
            response.put("success", true);
            response.put("message", "Cleanup completed");

        } catch (Exception e) {
            logger.error("Failed to cleanup conferences: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("error", "Cleanup failed: " + e.getMessage());
        }

        return ResponseEntity.ok(response);
    }
}