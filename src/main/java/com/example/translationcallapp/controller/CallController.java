package com.example.translationcallapp.controller;

import com.example.translationcallapp.service.BotParticipantService;
import com.example.translationcallapp.service.SecureConferenceService;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Say;
import com.twilio.twiml.voice.Dial;
import com.twilio.twiml.voice.Conference;
import com.twilio.twiml.voice.Hangup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/call")
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
     * Get Twilio access token for client
     */
    @GetMapping("/token")
    public ResponseEntity<Map<String, Object>> getToken(@RequestParam String identity) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            String token = botParticipantService.generateAccessToken(identity);
            
            response.put("success", true);
            response.put("token", token);
            response.put("identity", identity);
            
        } catch (Exception e) {
            logger.error("Failed to generate token: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("error", "Failed to generate access token: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
        
        return ResponseEntity.ok(response);
    }

    /**
     * Store call data before initiating call
     */
    @PostMapping("/store-call-data")
    public ResponseEntity<Map<String, Object>> storeCallData(@RequestBody Map<String, Object> callData) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            String callId = (String) callData.get("callId");
            String targetPhoneNumber = (String) callData.get("targetPhoneNumber");
            String sourceLanguage = (String) callData.get("sourceLanguage");
            String targetLanguage = (String) callData.get("targetLanguage");
            
            // Store the call data for webhook processing
            botParticipantService.storeCallData(callId, targetPhoneNumber, sourceLanguage, targetLanguage);
            
            response.put("success", true);
            response.put("message", "Call data stored successfully");
            response.put("callId", callId);
            
        } catch (Exception e) {
            logger.error("Failed to store call data: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("error", "Failed to store call data: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
        
        return ResponseEntity.ok(response);
    }

    /**
     * Handle incoming voice calls and generate TwiML
     */
    @PostMapping(value = "/voice/incoming", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> handleIncomingCall(
            @RequestParam(required = false) String CallSid,
            @RequestParam(required = false) String From,
            @RequestParam(required = false) String To,
            @RequestParam(required = false) String conferenceName,
            HttpServletRequest request) {
        
        try {
            // Debug logging - add this temporarily
            logger.info("All request parameters: {}", request.getParameterMap());
            logger.info("Incoming call: CallSid={}, From={}, To={}, ConferenceName={}", CallSid, From, To, conferenceName);
            
            String finalConferenceName = null;
            String sourceLanguage = "en-US";
            String targetLanguage = "es-ES";
            String targetPhoneNumber = null;
            
            // **PRIORITY 1: Use conferenceName parameter if provided**
            if (conferenceName != null && !conferenceName.trim().isEmpty()) {
                finalConferenceName = conferenceName;
                logger.info("Using conference name from URL parameter: {}", finalConferenceName);
                
                // Look up languages from stored call data
                for (Map.Entry<String, BotParticipantService.ParticipantMetadata> entry : 
                     botParticipantService.getStoredCallData().entrySet()) {
                    BotParticipantService.ParticipantMetadata metadata = entry.getValue();
                    if (metadata.getConferenceName() != null && 
                        metadata.getConferenceName().equals(finalConferenceName)) {
                        sourceLanguage = metadata.getSourceLanguage() != null ? metadata.getSourceLanguage() : "en-US";
                        targetLanguage = metadata.getTargetLanguage() != null ? metadata.getTargetLanguage() : "es-ES";
                        targetPhoneNumber = metadata.getPhoneNumber();
                        logger.info("Found stored data: target={}, languages={}→{}", targetPhoneNumber, sourceLanguage, targetLanguage);
                        break;
                    }
                }
            } else {
                // **FALLBACK: Look up stored call data to determine conference**
                for (Map.Entry<String, BotParticipantService.ParticipantMetadata> entry : 
                     botParticipantService.getStoredCallData().entrySet()) {
                    BotParticipantService.ParticipantMetadata metadata = entry.getValue();
                    
                    // Match by conference name from the call ID
                    if (metadata.getConferenceName() != null && 
                        entry.getKey().startsWith("translation-call-")) {
                        finalConferenceName = metadata.getConferenceName();
                        sourceLanguage = metadata.getSourceLanguage() != null ? metadata.getSourceLanguage() : "en-US";
                        targetLanguage = metadata.getTargetLanguage() != null ? metadata.getTargetLanguage() : "es-ES";
                        targetPhoneNumber = metadata.getPhoneNumber();
                        break;
                    }
                }
                
                // Default conference name if none found
                if (finalConferenceName == null) {
                    finalConferenceName = "translation-call-" + System.currentTimeMillis();
                }
            }
            
            logger.info("Final conference setup: name={}, target={}, languages={}→{}", 
                       finalConferenceName, targetPhoneNumber, sourceLanguage, targetLanguage);
            
            // **ADD TRANSLATION BOT** - Only once per conference
            if (!botParticipantService.isBotInConference(finalConferenceName)) {
                try {
                    String botCallSid = botParticipantService.addTranslationBot(
                        finalConferenceName, sourceLanguage, targetLanguage
                    );
                    logger.info("Translation bot added to conference {}: {}", finalConferenceName, botCallSid);
                } catch (Exception e) {
                    logger.error("Failed to add translation bot to conference {}: {}", finalConferenceName, e.getMessage());
                }
            } else {
                logger.info("Bot already exists in conference: {}", finalConferenceName);
            }
            
            // **AUTOMATIC PARTICIPANT ADDITION** - Only for initial frontend calls that have conferenceName parameter
            if (conferenceName != null && targetPhoneNumber != null && !targetPhoneNumber.trim().isEmpty()) {
                try {
                    logger.info("Auto-adding participant {} to conference {}", targetPhoneNumber, finalConferenceName);
                    String participantCallSid = botParticipantService.addParticipantToConference(
                        finalConferenceName, targetPhoneNumber);
                    logger.info("Successfully added participant: callSid={}", participantCallSid);
                } catch (Exception e) {
                    logger.warn("Failed to auto-add participant {}: {}", targetPhoneNumber, e.getMessage());
                    // Continue with conference creation even if participant addition fails
                }
            } else {
                logger.info("Skipping participant addition - conferenceName: {}, targetPhoneNumber: {}", 
                           conferenceName, targetPhoneNumber);
            }
            
            // Generate TwiML to connect caller to conference
            VoiceResponse voiceResponse = new VoiceResponse.Builder()
                .say(new Say.Builder("Welcome to Talk Translate. Connecting you to the translation conference.")
                    .voice(Say.Voice.ALICE)
                    .build())
                .dial(new Dial.Builder()
                    .conference(new Conference.Builder(finalConferenceName)
                        .startConferenceOnEnter(true)
                        .endConferenceOnExit(false)
                        .build())
                    .build())
                .build();
            
            String twimlResponse = voiceResponse.toXml();
            logger.info("Generated TwiML for call {} using conference {}: {}", CallSid, finalConferenceName, twimlResponse);
            
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(twimlResponse);
                
        } catch (Exception e) {
            logger.error("Error handling incoming call {}: {}", CallSid, e.getMessage(), e);
            
            // Return error TwiML
            VoiceResponse errorResponse = new VoiceResponse.Builder()
                .say(new Say.Builder("Sorry, there was an error connecting your call. Please try again later.")
                    .voice(Say.Voice.ALICE)
                    .build())
                .hangup(new Hangup.Builder().build())
                .build();
                
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(errorResponse.toXml());
        }
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
            
            if (conferenceInfo != null) {
                response.put("status", conferenceInfo.getStatus());
            } else {
                response.put("status", "not_found");
            }
            
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
            
            // Also remove from secure conference tracking
            secureConferenceService.removeConference(conferenceSid);

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

    /**
     * Get service statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getServiceStats() {
        Map<String, Object> response = new HashMap<>();
        
        try {
            var botStats = botParticipantService.getStats();
            var conferenceStats = secureConferenceService.getConferenceStats();
            
            response.put("success", true);
            response.put("botStats", Map.of(
                "activeBots", botStats.getActiveBots(),
                "activeConferences", botStats.getActiveConferences(),
                "maxConcurrentBots", botStats.getMaxConcurrentBots(),
                "availableRateLimit", botStats.getAvailableRateLimit()
            ));
            response.put("conferenceStats", Map.of(
                "activeConferences", conferenceStats.getActiveConferences(),
                "oldestConferenceAgeMinutes", conferenceStats.getOldestConferenceAgeMinutes()
            ));
            response.put("serviceHealth", botParticipantService.isHealthy());

        } catch (Exception e) {
            logger.error("Failed to get service stats: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("error", "Failed to get statistics: " + e.getMessage());
        }

        return ResponseEntity.ok(response);
    }
}