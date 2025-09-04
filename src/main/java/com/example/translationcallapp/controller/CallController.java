package com.example.translationcallapp.controller;

import com.example.translationcallapp.service.GoogleCloudService;
import com.twilio.jwt.accesstoken.AccessToken;
import com.twilio.jwt.accesstoken.VoiceGrant;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Dial;
import com.twilio.twiml.voice.Say;
import com.twilio.type.PhoneNumber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/call")
@CrossOrigin(origins = "*")
public class CallController {

    @Autowired
    private GoogleCloudService googleCloudService;

    @Value("${twilio.account.sid}")
    private String accountSid;

    @Value("${twilio.api.key}")
    private String apiKey;

    @Value("${twilio.api.secret}")
    private String apiSecret;

    @Value("${twilio.twiml.app.sid}")
    private String twimlAppSid;

    @Value("${twilio.phone.number:+1234567890}")
    private String twilioPhoneNumber;

    public CallController() {
        log.info("Twilio initialized successfully");
    }

    @GetMapping("/token")
    public ResponseEntity<Map<String, String>> generateToken(@RequestParam String identity) {
        try {
            log.info("Generating token for identity: {}", identity);
            
            VoiceGrant voiceGrant = new VoiceGrant();
            voiceGrant.setIncomingAllow(true);
            voiceGrant.setOutgoingApplicationSid(twimlAppSid);

            AccessToken accessToken = new AccessToken.Builder(accountSid, apiKey, apiSecret)
                    .identity(identity)
                    .grant(voiceGrant)
                    .build();

            Map<String, String> response = new HashMap<>();
            response.put("token", accessToken.toJwt());
            response.put("identity", identity);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to generate token", e);
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to generate token: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Main webhook handler - receives calls from TwiML App
     * This method will receive all the parameters passed from device.connect()
     */
    @PostMapping("/voice/incoming")
    public ResponseEntity<String> handleIncomingCall(@RequestParam Map<String, String> allParams) {
        try {
            // Log all parameters received from Twilio (as per their guidance)
            log.info("=== INCOMING CALL WEBHOOK ===");
            log.info("All parameters received: {}", allParams);
            
            // Standard Twilio parameters
            String from = allParams.get("From");
            String to = allParams.get("To");
            String callSid = allParams.get("CallSid");
            String accountSid = allParams.get("AccountSid");
            
            // Custom parameters sent from device.connect() (per Twilio's guidance)
            String targetPhoneNumber = allParams.get("targetPhoneNumber");
            String conferenceName = allParams.get("conferenceName");
            String sourceLanguage = allParams.get("sourceLanguage");
            String targetLanguage = allParams.get("targetLanguage");
            String callType = allParams.get("callType");
            
            log.info("Standard params - From: {}, To: {}, CallSid: {}", from, to, callSid);
            log.info("Custom params - Target: {}, Conference: {}, Languages: {} -> {}", 
                    targetPhoneNumber, conferenceName, sourceLanguage, targetLanguage);

            // Validate that we have the required parameters
            if (targetPhoneNumber == null || conferenceName == null) {
                log.error("Missing required parameters: targetPhoneNumber={}, conferenceName={}", 
                         targetPhoneNumber, conferenceName);
                
                VoiceResponse errorResponse = new VoiceResponse.Builder()
                    .say(new Say.Builder("Sorry, there was an error with the call parameters. Please try again.")
                        .voice(Say.Voice.ALICE)
                        .language(Say.Language.EN_US)
                        .build())
                    .build();
                    
                return ResponseEntity.ok()
                    .header("Content-Type", "application/xml")
                    .body(errorResponse.toXml());
            }

            // Step 1: Put the browser caller (from) into the conference
            Say welcomeMessage = new Say.Builder("Welcome to the translation service. Connecting you to " + targetPhoneNumber + ". Please wait.")
                    .voice(Say.Voice.ALICE)
                    .language(Say.Language.EN_US)
                    .build();

            // Create conference dial for the browser caller (FIXED: removed invalid methods)
            com.twilio.twiml.voice.Conference conference = new com.twilio.twiml.voice.Conference.Builder(conferenceName)
                    .record(com.twilio.twiml.voice.Conference.Record.RECORD_FROM_START)
                    .statusCallback("https://talktranslate-backend-production.up.railway.app/api/call/conference/status")
                    .waitUrl("http://twimlets.com/holdmusic?Bucket=com.twilio.music.classical")
                    .build();

            Dial dial = new Dial.Builder()
                    .conference(conference)
                    .build();

            // Step 2: Initiate outbound call to target phone number (this happens after TwiML response)
            // We do this asynchronously so the browser caller gets connected to conference first
            initiateOutboundCallAsync(targetPhoneNumber, conferenceName, targetLanguage);

            // Return TwiML for browser caller
            VoiceResponse voiceResponse = new VoiceResponse.Builder()
                    .say(welcomeMessage)
                    .dial(dial)
                    .build();

            log.info("Returning TwiML for browser caller to join conference: {}", conferenceName);
            return ResponseEntity.ok()
                    .header("Content-Type", "application/xml")
                    .body(voiceResponse.toXml());

        } catch (Exception e) {
            log.error("Failed to handle incoming call", e);
            
            VoiceResponse errorResponse = new VoiceResponse.Builder()
                .say(new Say.Builder("Sorry, there was an error processing your call. Please try again.")
                    .voice(Say.Voice.ALICE)
                    .language(Say.Language.EN_US)
                    .build())
                .build();
                
            return ResponseEntity.ok()
                .header("Content-Type", "application/xml")
                .body(errorResponse.toXml());
        }
    }

    /**
     * Async method to call target phone number and connect them to conference
     */
    private void initiateOutboundCallAsync(String targetPhoneNumber, String conferenceName, String targetLanguage) {
        // Run in separate thread to avoid blocking the TwiML response
        new Thread(() -> {
            try {
                Thread.sleep(2000); // Give browser caller time to join conference first
                
                log.info("Initiating outbound call to: {} for conference: {}", targetPhoneNumber, conferenceName);
                
                // Create outbound call to target number that will connect them to the same conference
                String webhookUrl = "https://talktranslate-backend-production.up.railway.app/api/call/connect-target" +
                                  "?conferenceName=" + conferenceName + 
                                  "&targetLanguage=" + (targetLanguage != null ? targetLanguage : "es-ES");
                
                Call call = Call.creator(
                    new PhoneNumber(targetPhoneNumber),
                    new PhoneNumber(twilioPhoneNumber), // Your Twilio number
                    URI.create(webhookUrl)
                ).create();

                log.info("Successfully initiated outbound call: {} to join conference: {}", 
                        call.getSid(), conferenceName);
                
            } catch (Exception e) {
                log.error("Failed to initiate outbound call to: " + targetPhoneNumber, e);
            }
        }).start();
    }

    /**
     * Webhook for connecting target phone number to conference
     */
    @PostMapping("/connect-target")
    public ResponseEntity<String> connectTargetToConference(
            @RequestParam String conferenceName,
            @RequestParam(defaultValue = "es-ES") String targetLanguage) {
        try {
            log.info("Connecting target phone to conference: {} with language: {}", conferenceName, targetLanguage);

            Say welcomeMessage = new Say.Builder("Hello! You are receiving a translated call. You will be connected shortly.")
                    .voice(Say.Voice.ALICE)
                    .language(Say.Language.EN_US)
                    .build();

            // FIXED: removed invalid statusCallbackEvent method
            com.twilio.twiml.voice.Conference conference = new com.twilio.twiml.voice.Conference.Builder(conferenceName)
                    .record(com.twilio.twiml.voice.Conference.Record.RECORD_FROM_START)
                    .statusCallback("https://talktranslate-backend-production.up.railway.app/api/call/conference/status")
                    .build();

            Dial dial = new Dial.Builder()
                    .conference(conference)
                    .build();

            VoiceResponse voiceResponse = new VoiceResponse.Builder()
                    .say(welcomeMessage)
                    .dial(dial)
                    .build();

            return ResponseEntity.ok()
                    .header("Content-Type", "application/xml")
                    .body(voiceResponse.toXml());

        } catch (Exception e) {
            log.error("Failed to connect target to conference", e);
            return ResponseEntity.internalServerError()
                    .body("<Response><Say>Error connecting to conference</Say></Response>");
        }
    }

    /**
     * Conference status webhook - handles conference events
     */
    @PostMapping("/conference/status")
    public ResponseEntity<String> handleConferenceStatus(@RequestParam Map<String, String> params) {
        log.info("Conference status callback: {}", params);
        
        String event = params.get("StatusCallbackEvent");
        String conferenceName = params.get("FriendlyName");
        String participantSid = params.get("CallSid");
        
        if (event != null) {
            switch (event) {
                case "conference-start":
                    log.info("Conference started: {}", conferenceName);
                    break;
                case "participant-join":
                    log.info("Participant joined conference {}: {}", conferenceName, participantSid);
                    break;
                case "participant-leave":
                    log.info("Participant left conference {}: {}", conferenceName, participantSid);
                    break;
                case "conference-end":
                    log.info("Conference ended: {}", conferenceName);
                    break;
                default:
                    log.info("Conference event: {} for conference: {}", event, conferenceName);
            }
        }
        
        return ResponseEntity.ok("<Response></Response>");
    }
}