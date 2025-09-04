package com.example.translationcallapp.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.twilio.Twilio;
import com.twilio.jwt.accesstoken.AccessToken;
import com.twilio.jwt.accesstoken.VoiceGrant;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Conference;
import com.twilio.twiml.voice.Dial;
import com.twilio.twiml.voice.Say;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.PostConstruct;
import java.util.*;

@RestController
@RequestMapping("/api/call")
@CrossOrigin(origins = {"*"}, methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS}, allowedHeaders = "*")
public class CallController {

    private static final Logger logger = LoggerFactory.getLogger(CallController.class);

    @Value("${TWILIO_ACCOUNT_SID}")
    private String accountSid;

    @Value("${TWILIO_AUTH_TOKEN}")
    private String authToken;

    @Value("${TWILIO_API_KEY}")
    private String apiKey;

    @Value("${TWILIO_API_SECRET}")
    private String apiSecret;

    @Value("${TWILIO_TWIML_APP_SID}")
    private String twimlAppSid;

    @Value("${TWILIO_PHONE_NUMBER}")
    private String twilioPhoneNumber;

    @PostConstruct
    public void init() {
        try {
            Twilio.init(accountSid, authToken);
            logger.info("Twilio initialized successfully");
        } catch (Exception e) {
            logger.error("Failed to initialize Twilio", e);
        }
    }

    @GetMapping(value = "/token", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> generateToken(@RequestParam(required = false) String identity) {
        try {
            if (identity == null || identity.trim().isEmpty()) {
                identity = "user_" + System.currentTimeMillis();
            }

            logger.info("Generating token for identity: {}", identity);

            // Create Voice Grant
            VoiceGrant voiceGrant = new VoiceGrant();
            voiceGrant.setOutgoingApplicationSid(twimlAppSid);
            voiceGrant.setIncomingAllow(true);

            // Create Access Token using the correct API
            AccessToken accessToken = new AccessToken.Builder(accountSid, apiKey, apiSecret)
                    .identity(identity)
                    .grant(voiceGrant)
                    .build();

            Map<String, String> response = new HashMap<>();
            response.put("token", accessToken.toJwt());
            response.put("identity", identity);

            logger.info("Token generated successfully for identity: {}", identity);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Failed to generate token", e);
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to generate token: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @PostMapping("/initiate-call")
    public ResponseEntity<?> initiateTranslationCall(@RequestBody Map<String, String> request) {
        try {
            String callerIdentity = request.get("callerIdentity");
            String targetNumber = request.get("targetNumber");
            String sourceLanguage = request.get("sourceLanguage");
            String targetLanguage = request.get("targetLanguage");
            
            if (targetNumber == null || targetNumber.trim().isEmpty()) {
                Map<String, String> errorResponse = new HashMap<>();
                errorResponse.put("error", "Target number is required");
                return ResponseEntity.badRequest().body(errorResponse);
            }
            
            logger.info("Initiating translation call - Caller: {}, Target: {}, Languages: {} -> {}", 
                callerIdentity, targetNumber, sourceLanguage, targetLanguage);
            
            // Create unique conference name
            String conferenceName = "translation-" + System.currentTimeMillis();
            
            // Use Twilio REST API to create call to target number
            com.twilio.rest.api.v2010.account.Call call = com.twilio.rest.api.v2010.account.Call.creator(
                new PhoneNumber(targetNumber),
                new PhoneNumber(twilioPhoneNumber),
                URI.create("https://talktranslate-backend-production.up.railway.app/api/call/connect-target")
            )
            .setMachineDetection("Enable")
            .setStatusCallback(URI.create("https://talktranslate-backend-production.up.railway.app/api/call/voice/status"))
            .setStatusCallbackEvent(Arrays.asList("initiated", "ringing", "answered", "completed"))
            // Pass conference name and language info as URL parameters
            .setUrl(URI.create("https://talktranslate-backend-production.up.railway.app/api/call/connect-target?conference=" + conferenceName + "&sourceLanguage=" + sourceLanguage + "&targetLanguage=" + targetLanguage))
            .create();
            
            logger.info("Created outbound call to target: {}, CallSid: {}", targetNumber, call.getSid());
            
            // Return conference name to frontend so browser caller can join
            Map<String, String> response = new HashMap<>();
            response.put("conferenceName", conferenceName);
            response.put("callSid", call.getSid());
            response.put("status", "initiated");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Failed to initiate translation call", e);
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to initiate call: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    @PostMapping(value = "/connect-target", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> connectTargetToConference(@RequestParam Map<String, String> params) {
        try {
            String conferenceName = params.get("conference");
            String sourceLanguage = params.get("sourceLanguage");
            String targetLanguage = params.get("targetLanguage");
            String callSid = params.get("CallSid");
            
            logger.info("Connecting target to conference: {}, CallSid: {}", conferenceName, callSid);
            
            // Create TwiML to connect target to conference
            VoiceResponse response = new VoiceResponse.Builder()
                .say(new Say.Builder("You have an incoming translation call. Connecting now.").build())
                .dial(new Dial.Builder()
                    .conference(new Conference.Builder(conferenceName)
                        .startConferenceOnEnter(true)
                        .endConferenceOnExit(true)
                        // Enable recording for translation processing
                        .record("record-from-start")
                        .build())
                    .build())
                .build();

            return ResponseEntity.ok(response.toXml());
            
        } catch (Exception e) {
            logger.error("Error connecting target to conference", e);
            
            VoiceResponse errorResponse = new VoiceResponse.Builder()
                .say(new Say.Builder("Sorry, there was an error connecting your call.").build())
                .build();
                
            return ResponseEntity.ok(errorResponse.toXml());
        }
    }

    @PostMapping(value = "/connect-caller", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> connectCallerToConference(@RequestParam Map<String, String> params) {
        try {
            String conferenceName = params.get("conference");
            String callSid = params.get("CallSid");
            
            logger.info("Connecting browser caller to conference: {}, CallSid: {}", conferenceName, callSid);
            
            VoiceResponse response = new VoiceResponse.Builder()
                .say(new Say.Builder("Welcome to the translation service. You are now connected.").build())
                .dial(new Dial.Builder()
                    .conference(new Conference.Builder(conferenceName)
                        .startConferenceOnEnter(false)  // Don't start until both parties join
                        .endConferenceOnExit(true)
                        .record("record-from-start")
                        .build())
                    .build())
                .build();

            return ResponseEntity.ok(response.toXml());
            
        } catch (Exception e) {
            logger.error("Error connecting caller to conference", e);
            
            VoiceResponse errorResponse = new VoiceResponse.Builder()
                .say(new Say.Builder("Sorry, there was an error connecting your call.").build())
                .build();
                
            return ResponseEntity.ok(errorResponse.toXml());
        }
    }

    @PostMapping(value = "/voice/incoming", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> handleIncomingCall(
            @RequestParam Map<String, String> params) {
        
        try {
            String from = params.get("From");
            String to = params.get("To");
            String callSid = params.get("CallSid");
            
            logger.info("Incoming call - From: {}, To: {}, CallSid: {}", from, to, callSid);
            
            // Create conference room based on call participants
            String conferenceName = "translation-call-" + callSid;
            
            VoiceResponse response = new VoiceResponse.Builder()
                .say(new Say.Builder("Welcome to the translation service. Connecting you now.").build())
                .dial(new Dial.Builder()
                    .conference(new Conference.Builder(conferenceName)
                        .startConferenceOnEnter(true)
                        .endConferenceOnExit(false)
                        .build())
                    .build())
                .build();

            return ResponseEntity.ok(response.toXml());
            
        } catch (Exception e) {
            logger.error("Error handling incoming call", e);
            
            VoiceResponse errorResponse = new VoiceResponse.Builder()
                .say(new Say.Builder("Sorry, there was an error processing your call.").build())
                .build();
                
            return ResponseEntity.ok(errorResponse.toXml());
        }
    }

    @PostMapping("/voice/status")
    public ResponseEntity<String> handleCallStatus(@RequestParam Map<String, String> params) {
        String callSid = params.get("CallSid");
        String callStatus = params.get("CallStatus");
        
        logger.info("Call status update - CallSid: {}, Status: {}", callSid, callStatus);
        
        return ResponseEntity.ok("OK");
    }

    @PostMapping("/conference/status")
    public ResponseEntity<String> handleConferenceStatus(@RequestParam Map<String, String> params) {
        String conferenceSid = params.get("ConferenceSid");
        String statusCallbackEvent = params.get("StatusCallbackEvent");
        
        logger.info("Conference status update - ConferenceSid: {}, Event: {}", conferenceSid, statusCallbackEvent);
        
        return ResponseEntity.ok("OK");
    }

    @PostMapping(value = "/conference/connect", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> handleConferenceConnect(@RequestParam Map<String, String> params) {
        try {
            String callSid = params.get("CallSid");
            String from = params.get("From");
            
            logger.info("Conference connect - CallSid: {}, From: {}", callSid, from);
            
            // Create the same conference name as the original caller
            String conferenceName = "translation-call-" + callSid;
            
            VoiceResponse response = new VoiceResponse.Builder()
                .dial(new Dial.Builder()
                    .conference(new Conference.Builder(conferenceName)
                        .startConferenceOnEnter(true)
                        .endConferenceOnExit(true)
                        .build())
                    .build())
                .build();

            return ResponseEntity.ok(response.toXml());
            
        } catch (Exception e) {
            logger.error("Error handling conference connect", e);
            
            VoiceResponse errorResponse = new VoiceResponse.Builder()
                .say(new Say.Builder("Sorry, there was an error connecting to the conference.").build())
                .build();
                
            return ResponseEntity.ok(errorResponse.toXml());
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> status = new HashMap<>();
        status.put("status", "healthy");
        status.put("service", "translation-call-app");
        return ResponseEntity.ok(status);
    }
}