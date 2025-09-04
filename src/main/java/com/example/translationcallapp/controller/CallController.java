package com.example.translationcallapp.controller;

import com.example.translationcallapp.service.GoogleCloudService;
import com.twilio.jwt.accesstoken.AccessToken;
import com.twilio.jwt.accesstoken.VoiceGrant;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.rest.api.v2010.account.conference.Participant;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Conference;
import com.twilio.twiml.voice.Dial;
import com.twilio.twiml.voice.Say;
import com.twilio.type.PhoneNumber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
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
    private String apiKeySid;

    @Value("${twilio.api.secret}")
    private String apiKeySecret;

    @Value("${twilio.twiml.app.sid}")
    private String twimlAppSid;

    @Value("${twilio.phone.number}")
    private String twilioPhoneNumber;

    public CallController() {
        log.info("Twilio initialized successfully");
    }

    @GetMapping("/token")
    public ResponseEntity<Map<String, String>> generateToken(
            @RequestParam(defaultValue = "anonymous") String identity) {
        try {
            // Create access token
            AccessToken accessToken = new AccessToken.Builder(
                    accountSid,
                    apiKeySid,
                    apiKeySecret
            ).identity(identity).build();

            // Create Voice grant
            VoiceGrant voiceGrant = new VoiceGrant();
            voiceGrant.setOutgoingApplicationSid(twimlAppSid);
            voiceGrant.setIncomingAllow(true);

            // Add grant to token
            accessToken.addGrant(voiceGrant);

            Map<String, String> response = new HashMap<>();
            response.put("identity", identity);
            response.put("token", accessToken.toJwt());

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response);
        } catch (Exception e) {
            log.error("Error generating token", e);
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to generate token: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    @PostMapping("/initiate-call")
    public ResponseEntity<Map<String, String>> initiateCall(@RequestBody Map<String, String> request) {
        try {
            String targetPhoneNumber = request.get("targetPhoneNumber");
            String sourceLanguage = request.get("sourceLanguage");
            String targetLanguage = request.get("targetLanguage");
            
            log.info("Initiating call to {} with translation from {} to {}", 
                    targetPhoneNumber, sourceLanguage, targetLanguage);

            // Generate unique conference name
            String conferenceName = "translation-call-" + System.currentTimeMillis();
            
            // Create outbound call to target phone number
            String webhookUrl = "https://talktranslate-backend-production.up.railway.app/api/call/connect-target";
            
            Call call = Call.creator(
                    new PhoneNumber(targetPhoneNumber), // to
                    new PhoneNumber(twilioPhoneNumber),  // from
                    URI.create(webhookUrl + "?conferenceName=" + conferenceName + 
                              "&sourceLanguage=" + sourceLanguage + 
                              "&targetLanguage=" + targetLanguage)
            ).create();

            log.info("Created outbound call: {}", call.getSid());

            Map<String, String> response = new HashMap<>();
            response.put("conferenceName", conferenceName);
            response.put("callSid", call.getSid());
            response.put("status", "initiated");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error initiating call", e);
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to initiate call: " + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    @PostMapping(value = "/connect-target", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> connectTarget(
            @RequestParam String conferenceName,
            @RequestParam(required = false) String sourceLanguage,
            @RequestParam(required = false) String targetLanguage) {
        try {
            log.info("Connecting target to conference: {}", conferenceName);

            VoiceResponse response = new VoiceResponse.Builder()
                    .say(new Say.Builder("You are being connected to a translation call.")
                            .voice(Say.Voice.ALICE)
                            .build())
                    .dial(new Dial.Builder()
                            .conference(new Conference.Builder(conferenceName)
                                    .startConferenceOnEnter(true)
                                    .endConferenceOnExit(false)
                                    .record(Conference.Record.RECORD_FROM_START)
                                    .recordingStatusCallback("https://talktranslate-backend-production.up.railway.app/api/call/recording-status")
                                    .build())
                            .build())
                    .build();

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_XML)
                    .body(response.toXml());
        } catch (Exception e) {
            log.error("Error connecting target to conference", e);
            
            VoiceResponse errorResponse = new VoiceResponse.Builder()
                    .say(new Say.Builder("Sorry, there was an error connecting your call.")
                            .voice(Say.Voice.ALICE)
                            .build())
                    .build();
            
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.APPLICATION_XML)
                    .body(errorResponse.toXml());
        }
    }

    @PostMapping(value = "/connect-caller", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> connectCaller(
            @RequestParam(required = false) String conferenceName,
            @RequestParam(required = false) String sourceLanguage,
            @RequestParam(required = false) String targetLanguage) {
        try {
            log.info("Connecting caller to conference: {}", conferenceName);

            VoiceResponse response = new VoiceResponse.Builder()
                    .say(new Say.Builder("Welcome to the translation service. Connecting you now.")
                            .voice(Say.Voice.ALICE)
                            .build())
                    .dial(new Dial.Builder()
                            .conference(new Conference.Builder(conferenceName != null ? conferenceName : "default-conference")
                                    .startConferenceOnEnter(true)
                                    .endConferenceOnExit(true)
                                    .record(Conference.Record.RECORD_FROM_START)
                                    .recordingStatusCallback("https://talktranslate-backend-production.up.railway.app/api/call/recording-status")
                                    .build())
                            .build())
                    .build();

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_XML)
                    .body(response.toXml());
        } catch (Exception e) {
            log.error("Error connecting caller to conference", e);
            
            VoiceResponse errorResponse = new VoiceResponse.Builder()
                    .say(new Say.Builder("Sorry, there was an error connecting your call.")
                            .voice(Say.Voice.ALICE)
                            .build())
                    .build();
            
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.APPLICATION_XML)
                    .body(errorResponse.toXml());
        }
    }

    @PostMapping("/recording-status")
    public ResponseEntity<String> recordingStatus(@RequestBody Map<String, String> request) {
        try {
            log.info("Recording status callback: {}", request);
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            log.error("Error handling recording status", e);
            return ResponseEntity.internalServerError().body("Error");
        }
    }

    @GetMapping("/conference/participants")
    public ResponseEntity<Map<String, Object>> getConferenceParticipants(
            @RequestParam String conferenceName) {
        try {
            // Get conference participants (this would need additional Twilio API calls)
            Map<String, Object> response = new HashMap<>();
            response.put("conferenceName", conferenceName);
            response.put("participants", List.of()); // Placeholder
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error getting conference participants", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // Legacy endpoint - kept for compatibility
    @PostMapping(value = "/voice/incoming", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> handleIncomingCall(
            @RequestParam(required = false) String From,
            @RequestParam(required = false) String To,
            @RequestParam(required = false) String CallSid) {
        try {
            log.info("Legacy incoming call - From: {}, To: {}, CallSid: {}", From, To, CallSid);

            VoiceResponse response = new VoiceResponse.Builder()
                    .say(new Say.Builder("Welcome to the translation service.")
                            .voice(Say.Voice.ALICE)
                            .build())
                    .dial(new Dial.Builder()
                            .conference(new Conference.Builder("legacy-conference")
                                    .startConferenceOnEnter(true)
                                    .endConferenceOnExit(true)
                                    .build())
                            .build())
                    .build();

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_XML)
                    .body(response.toXml());
        } catch (Exception e) {
            log.error("Error handling legacy incoming call", e);
            
            VoiceResponse errorResponse = new VoiceResponse.Builder()
                    .say(new Say.Builder("Sorry, there was an error.")
                            .voice(Say.Voice.ALICE)
                            .build())
                    .build();
            
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.APPLICATION_XML)
                    .body(errorResponse.toXml());
        }
    }
}