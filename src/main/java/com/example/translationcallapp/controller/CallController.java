package com.example.translationcallapp.controller;

import com.example.translationcallapp.service.BotParticipantService;
import com.example.translationcallapp.service.OpenAITranslationService;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Conference;
import com.twilio.twiml.voice.Dial;
import com.twilio.twiml.voice.Say;
import com.twilio.twiml.voice.Connect;
import com.twilio.twiml.voice.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * REST Controller for handling Twilio webhook calls and call management
 */
@RestController
@RequestMapping("/api/calls")
public class CallController {
    
    private static final Logger logger = LoggerFactory.getLogger(CallController.class);
    
    @Autowired
    private BotParticipantService botParticipantService;
    
    @Autowired
    private OpenAITranslationService translationService;
    
    @Value("${app.websocket.stream-url:wss://your-domain.com/media-stream}")
    private String streamUrl;
    
    @Value("${app.twilio.conference-name:translation-conference}")
    private String defaultConferenceName;
    
    @Value("${app.twilio.welcome-message:Welcome to the translation service}")
    private String welcomeMessage;

    @PostConstruct
    public void init() {
        logger.info("CallController initialized with stream URL: {}", streamUrl);
        logger.info("Default conference name: {}", defaultConferenceName);
    }

    /**
     * Handles incoming calls from Twilio
     */
    @PostMapping(value = "/incoming", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> handleIncomingCall(
            @RequestParam Map<String, String> params,
            HttpServletRequest request) {
        
        logger.info("Incoming call from: {}", params.get("From"));
        logger.info("Call SID: {}", params.get("CallSid"));
        
        try {
            // Create TwiML response to join conference with translation
            VoiceResponse response = new VoiceResponse.Builder()
                .say(new Say.Builder(welcomeMessage).build())
                .dial(new Dial.Builder()
                    .conference(new Conference.Builder(defaultConferenceName)
                        .startConferenceOnEnter(true)
                        .endConferenceOnExit(false)
                        .build())
                    .build())
                .build();
            
            // Add bot participant for translation - FIXED: Using correct method signature
            String callSid = params.get("CallSid");
            // Changed from addTranslationBot(conferenceName, callSid) to addTranslationBot(conferenceName, "english")
            // Assuming default target language is English - you may want to make this configurable
            botParticipantService.addTranslationBot(defaultConferenceName, "english");
            
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(response.toXml());
                
        } catch (Exception e) {
            logger.error("Error handling incoming call: ", e);
            
            VoiceResponse errorResponse = new VoiceResponse.Builder()
                .say(new Say.Builder("Sorry, there was an error processing your call.").build())
                .build();
            
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(errorResponse.toXml());
        }
    }

    /**
     * Handles call status updates from Twilio
     */
    @PostMapping("/status")
    public ResponseEntity<String> handleCallStatus(
            @RequestParam Map<String, String> params) {
        
        String callSid = params.get("CallSid");
        String callStatus = params.get("CallStatus");
        
        logger.info("Call status update - SID: {}, Status: {}", callSid, callStatus);
        
        try {
            // Handle call completion cleanup
            if ("completed".equals(callStatus) || "failed".equals(callStatus)) {
                // FIXED: Changed from removeBot(callSid) to removeBot(conferenceName)
                // The removeBot method expects a conference SID, not a call SID
                botParticipantService.removeBot(defaultConferenceName);
                logger.info("Cleaned up resources for completed call: {}", callSid);
            }
            
            return ResponseEntity.ok("OK");
            
        } catch (Exception e) {
            logger.error("Error handling call status update: ", e);
            return ResponseEntity.ok("ERROR");
        }
    }

    /**
     * Creates a media stream connection for real-time audio processing
     */
    @PostMapping(value = "/stream", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> createMediaStream(
            @RequestParam Map<String, String> params) {
        
        String callSid = params.get("CallSid");
        logger.info("Creating media stream for call: {}", callSid);
        
        try {
            VoiceResponse response = new VoiceResponse.Builder()
                .say(new Say.Builder("Connecting to translation service").build())
                .connect(new Connect.Builder()
                    .stream(new Stream.Builder()
                        .url(streamUrl)
                        .build())
                    .build())
                .build();
            
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(response.toXml());
                
        } catch (Exception e) {
            logger.error("Error creating media stream: ", e);
            
            VoiceResponse errorResponse = new VoiceResponse.Builder()
                .say(new Say.Builder("Unable to connect to translation service").build())
                .build();
            
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(errorResponse.toXml());
        }
    }

    /**
     * REST endpoint to initiate a translated conference call
     */
    @PostMapping("/start-translation")
    public ResponseEntity<Map<String, Object>> startTranslationCall(
            @RequestBody Map<String, String> request) {
        
        String fromLanguage = request.get("fromLanguage");
        String toLanguage = request.get("toLanguage");
        String phoneNumber = request.get("phoneNumber");
        
        logger.info("Starting translation call: {} -> {}, Phone: {}", 
                   fromLanguage, toLanguage, phoneNumber);
        
        try {
            // Create conference and add bot
            String conferenceName = "translation-" + System.currentTimeMillis();
            
            // FIXED: Changed method call to match BotParticipantService signature
            // The addTranslationBot method doesn't return a String, it returns void
            botParticipantService.addTranslationBot(conferenceName, toLanguage, fromLanguage);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("conferenceName", conferenceName);
            response.put("fromLanguage", fromLanguage);
            response.put("toLanguage", toLanguage);
            response.put("message", "Translation call initiated successfully");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error starting translation call: ", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "Failed to start translation call: " + e.getMessage());
            
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * REST endpoint to add translation bot to existing conference
     */
    @PostMapping("/add-bot")
    public ResponseEntity<Map<String, Object>> addTranslationBot(
            @RequestBody Map<String, String> request) {
        
        String conferenceSid = request.get("conferenceSid");
        String targetLanguage = request.get("targetLanguage");
        String sourceLanguage = request.get("sourceLanguage");
        
        logger.info("Adding translation bot to conference: {}, {} -> {}", 
                   conferenceSid, sourceLanguage, targetLanguage);
        
        try {
            if (sourceLanguage != null && !sourceLanguage.isEmpty()) {
                // Use 3-parameter version
                botParticipantService.addTranslationBot(conferenceSid, targetLanguage, sourceLanguage);
            } else {
                // Use 2-parameter version
                botParticipantService.addTranslationBot(conferenceSid, targetLanguage);
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Translation bot added successfully");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error adding translation bot: ", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "Failed to add translation bot: " + e.getMessage());
            
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * REST endpoint to remove bot from conference
     */
    @PostMapping("/remove-bot")
    public ResponseEntity<Map<String, Object>> removeBot(
            @RequestBody Map<String, String> request) {
        
        String conferenceSid = request.get("conferenceSid");
        
        logger.info("Removing bot from conference: {}", conferenceSid);
        
        try {
            botParticipantService.removeBot(conferenceSid);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Bot removed successfully");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error removing bot: ", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "Failed to remove bot: " + e.getMessage());
            
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * REST endpoint to get conference status
     */
    @GetMapping("/conference/{conferenceSid}/status")
    public ResponseEntity<Map<String, Object>> getConferenceStatus(
            @PathVariable String conferenceSid) {
        
        try {
            int participantCount = botParticipantService.getParticipantCount(conferenceSid);
            boolean hasBot = botParticipantService.hasBot(conferenceSid);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("conferenceSid", conferenceSid);
            response.put("participantCount", participantCount);
            response.put("hasBot", hasBot);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error getting conference status: ", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "Failed to get conference status: " + e.getMessage());
            
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "CallController");
        health.put("timestamp", System.currentTimeMillis());
        
        return ResponseEntity.ok(health);
    }
}