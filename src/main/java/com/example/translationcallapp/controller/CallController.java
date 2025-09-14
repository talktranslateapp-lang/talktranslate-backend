package com.example.translationcallapp.controller;

import com.example.translationcallapp.service.BotParticipantService;
import com.example.translationcallapp.service.SecureConferenceService;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Say;
import com.twilio.twiml.voice.Dial;
import com.twilio.twiml.voice.Conference;
import com.twilio.type.PhoneNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Main controller for handling call-related API endpoints
 * Business logic for calls, conferences, and bot participants
 */
@RestController
@RequestMapping("/api/call")
public class CallController {
    
    private static final Logger logger = LoggerFactory.getLogger(CallController.class);
    
    @Autowired
    private SecureConferenceService conferenceService;
    
    @Autowired
    private BotParticipantService botParticipantService;

    /**
     * Generate access token for client applications
     */
    @GetMapping("/token")
    public ResponseEntity<Map<String, String>> getToken(@RequestParam String identity) {
        try {
            String token = conferenceService.generateAccessToken(identity);
            return ResponseEntity.ok(Map.of("token", token));
        } catch (Exception e) {
            logger.error("Error generating token: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", "Failed to generate token"));
        }
    }

    /**
     * Store call data for tracking and analytics
     */
    @PostMapping("/store-call-data")
    public ResponseEntity<String> storeCallData(@RequestBody Map<String, Object> callData) {
        try {
            logger.info("Storing call data: {}", callData);
            
            // Store call data in your database or analytics service
            // Implementation depends on your data storage requirements
            
            return ResponseEntity.ok("Call data stored successfully");
        } catch (Exception e) {
            logger.error("Error storing call data: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("Error storing call data");
        }
    }

    /**
     * Handle incoming voice calls
     * Returns TwiML response for call routing
     */
    @PostMapping("/voice/incoming")
    public ResponseEntity<String> handleIncomingCall(@RequestParam Map<String, String> params) {
        try {
            String from = params.get("From");
            String to = params.get("To");
            String callSid = params.get("CallSid");
            
            logger.info("Incoming call: From={}, To={}, CallSid={}", from, to, callSid);
            
            // Create TwiML response to route call to conference
            VoiceResponse response = new VoiceResponse.Builder()
                .say(new Say.Builder("Welcome to the translation service. Please wait while we connect you.")
                    .voice(Say.Voice.ALICE)
                    .language(Say.Language.EN_US)
                    .build())
                .dial(new Dial.Builder()
                    .conference(new Conference.Builder("translation-room-" + callSid)
                        .startConferenceOnEnter(true)
                        .endConferenceOnExit(false)
                        .waitUrl("http://twimlets.com/holdmusic?Bucket=com.twilio.music.ambient")
                        .build())
                    .build())
                .build();
            
            return ResponseEntity.ok()
                .header("Content-Type", "application/xml")
                .body(response.toXml());
                
        } catch (Exception e) {
            logger.error("Error handling incoming call: {}", e.getMessage(), e);
            
            VoiceResponse errorResponse = new VoiceResponse.Builder()
                .say(new Say.Builder("Sorry, there was an error processing your call. Please try again later.")
                    .voice(Say.Voice.ALICE)
                    .build())
                .build();
            
            return ResponseEntity.ok()
                .header("Content-Type", "application/xml")
                .body(errorResponse.toXml());
        }
    }

    /**
     * Handle conference join events
     * Called when participants join the conference
     */
    @PostMapping("/conference-join")
    public ResponseEntity<String> handleConferenceJoin(@RequestParam Map<String, String> params) {
        try {
            String conferenceName = params.get("ConferenceName");
            String participantCallSid = params.get("CallSid");
            
            logger.info("Participant joined conference: Conference={}, CallSid={}", 
                       conferenceName, participantCallSid);
            
            // Add translation bot to the conference after participant joins
            String botCallSid = botParticipantService.addBotToConference(conferenceName, participantCallSid);
            logger.info("Bot added to conference: BotCallSid={}", botCallSid);
            
            return ResponseEntity.ok("OK");
            
        } catch (Exception e) {
            logger.error("Error handling conference join: {}", e.getMessage(), e);
            return ResponseEntity.ok("ERROR");
        }
    }

    /**
     * Translation bot endpoint - handles bot call routing
     * This is called when the bot joins the conference
     */
    @PostMapping("/translation-bot")
    public ResponseEntity<String> translationBotEndpoint(@RequestParam Map<String, String> params) {
        try {
            String callSid = params.get("CallSid");
            String from = params.get("From");
            
            logger.info("Translation bot endpoint called: CallSid={}, From={}", callSid, from);
            
            // Create TwiML response for bot behavior
            VoiceResponse response = new VoiceResponse.Builder()
                .say(new Say.Builder("Translation bot is joining the conference")
                    .voice(Say.Voice.ALICE)
                    .language(Say.Language.EN_US)
                    .build())
                .build();
            
            return ResponseEntity.ok()
                .header("Content-Type", "application/xml")
                .body(response.toXml());
                
        } catch (Exception e) {
            logger.error("Error in translation bot endpoint: {}", e.getMessage(), e);
            
            VoiceResponse errorResponse = new VoiceResponse.Builder()
                .say(new Say.Builder("Bot connection error")
                    .voice(Say.Voice.ALICE)
                    .build())
                .build();
            
            return ResponseEntity.ok()
                .header("Content-Type", "application/xml")
                .body(errorResponse.toXml());
        }
    }

    /**
     * Create a new secure conference
     */
    @PostMapping("/create-conference")
    public ResponseEntity<Map<String, String>> createConference(@RequestBody Map<String, String> request) {
        try {
            String conferenceName = request.get("conferenceName");
            String creatorIdentity = request.get("creatorIdentity");
            
            String conferenceId = conferenceService.createSecureConference(conferenceName, creatorIdentity);
            
            return ResponseEntity.ok(Map.of(
                "conferenceId", conferenceId,
                "status", "created"
            ));
            
        } catch (Exception e) {
            logger.error("Error creating conference: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", "Failed to create conference"));
        }
    }

    /**
     * End a conference and clean up resources
     */
    @PostMapping("/end-conference")
    public ResponseEntity<Map<String, String>> endConference(@RequestBody Map<String, String> request) {
        try {
            String conferenceId = request.get("conferenceId");
            
            conferenceService.endConference(conferenceId);
            
            return ResponseEntity.ok(Map.of("status", "ended"));
            
        } catch (Exception e) {
            logger.error("Error ending conference: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", "Failed to end conference"));
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> healthCheck() {
        return ResponseEntity.ok(Map.of(
            "status", "healthy",
            "timestamp", String.valueOf(System.currentTimeMillis())
        ));
    }
}