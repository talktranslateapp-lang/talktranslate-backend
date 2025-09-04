package com.example.translationcallapp.controller;

import com.example.translationcallapp.service.GoogleCloudService;
import com.twilio.jwt.accesstoken.AccessToken;
import com.twilio.jwt.accesstoken.VoiceGrant;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.rest.api.v2010.account.CallCreator;
import com.twilio.type.PhoneNumber;
import com.twilio.twiml.TwiML;
import com.twilio.twiml.voice.Conference;
import com.twilio.twiml.voice.Dial;
import com.twilio.twiml.voice.Say;
import com.twilio.twiml.VoiceResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/call")
@CrossOrigin(origins = "*")
@Slf4j
public class CallController {

    @Value("${twilio.account.sid}")
    private String twilioAccountSid;

    @Value("${twilio.api.key}")
    private String twilioApiKey;

    @Value("${twilio.api.secret}")
    private String twilioApiSecret;

    @Value("${twilio.twiml.app.sid}")
    private String twimlAppSid;

    @Autowired
    private GoogleCloudService googleCloudService;

    // Store active conferences and their language settings
    private final Map<String, ConferenceInfo> activeConferences = new ConcurrentHashMap<>();

    static class ConferenceInfo {
        String sourceLanguage;
        String targetLanguage;
        String initiatorCallSid;
        String targetCallSid;
        
        ConferenceInfo(String sourceLanguage, String targetLanguage) {
            this.sourceLanguage = sourceLanguage;
            this.targetLanguage = targetLanguage;
        }
    }

    @GetMapping("/token")
    public ResponseEntity<Map<String, String>> getAccessToken(@RequestParam String identity) {
        try {
            log.info("Generating access token for identity: {}", identity);

            VoiceGrant voiceGrant = new VoiceGrant();
            voiceGrant.setOutgoingApplicationSid(twimlAppSid);
            voiceGrant.setIncomingAllow(true);

            AccessToken accessToken = new AccessToken.Builder(
                twilioAccountSid,
                twilioApiKey,
                twilioApiSecret
            )
                .identity(identity)
                .ttl(3600) // 1 hour
                .grant(voiceGrant)
                .build();

            Map<String, String> response = new HashMap<>();
            response.put("token", accessToken.toJwt());
            response.put("identity", identity);

            log.info("Access token generated successfully for: {}", identity);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error generating access token for identity {}: {}", identity, e.getMessage(), e);
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to generate access token");
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    @PostMapping("/initiate-call")
    public ResponseEntity<Map<String, Object>> initiateCall(
            @RequestBody Map<String, String> request) {
        try {
            String targetPhoneNumber = request.get("targetPhoneNumber");
            String sourceLanguage = request.get("sourceLanguage");
            String targetLanguage = request.get("targetLanguage");

            log.info("Initiating translation call to: {}", targetPhoneNumber);
            log.info("Languages: {} -> {}", sourceLanguage, targetLanguage);

            // Generate unique conference name
            String conferenceName = "translate-" + UUID.randomUUID().toString();
            
            // Store conference info
            activeConferences.put(conferenceName, new ConferenceInfo(sourceLanguage, targetLanguage));

            // Create outbound call to target phone number
            PhoneNumber to = new PhoneNumber(targetPhoneNumber);
            PhoneNumber from = new PhoneNumber("+1234567890"); // Replace with your Twilio number
            URI statusCallback = URI.create("https://talktranslate-backend-production.up.railway.app/api/call/status");
            
            CallCreator callCreator = Call.creator(to, from, 
                URI.create("https://talktranslate-backend-production.up.railway.app/api/call/connect-target?conference=" + conferenceName));
            
            callCreator.setStatusCallback(statusCallback);
            callCreator.setStatusCallbackMethod("POST");
            
            Call call = callCreator.create();

            log.info("Outbound call created successfully. CallSid: {}", call.getSid());

            // Store the call SID in conference info
            ConferenceInfo info = activeConferences.get(conferenceName);
            info.targetCallSid = call.getSid();

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("conferenceName", conferenceName);
            response.put("callSid", call.getSid());
            response.put("targetNumber", targetPhoneNumber);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error initiating call: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    @PostMapping(value = "/connect-target", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> connectTargetToConference(
            @RequestParam String conference,
            HttpServletRequest request) {
        try {
            log.info("Connecting target to conference: {}", conference);

            ConferenceInfo info = activeConferences.get(conference);
            if (info != null) {
                log.info("Conference found with languages: {} -> {}", info.sourceLanguage, info.targetLanguage);
            }

            Say welcomeMessage = new Say.Builder("Welcome to the translation service. You are now connected.")
                .voice(Say.Voice.ALICE)
                .language(Say.Language.EN)
                .build();

            Conference conferenceNoun = new Conference.Builder(conference)
                .startConferenceOnEnter(true)
                .endConferenceOnExit(false)
                .record(Conference.Record.RECORD_FROM_START)
                .recordingStatusCallback("https://talktranslate-backend-production.up.railway.app/api/call/recording")
                .build();

            Dial dial = new Dial.Builder()
                .conference(conferenceNoun)
                .build();

            VoiceResponse response = new VoiceResponse.Builder()
                .say(welcomeMessage)
                .dial(dial)
                .build();

            return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_XML)
                .body(response.toXml());

        } catch (Exception e) {
            log.error("Error connecting target to conference: {}", e.getMessage(), e);
            
            Say errorMessage = new Say.Builder("Sorry, there was an error connecting your call.")
                .voice(Say.Voice.ALICE)
                .build();

            VoiceResponse response = new VoiceResponse.Builder()
                .say(errorMessage)
                .build();

            return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_XML)
                .body(response.toXml());
        }
    }

    @PostMapping(value = "/connect-caller", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> connectCallerToConference(
            @RequestParam(required = false) String conferenceName,
            @RequestParam(required = false) String sourceLanguage,
            @RequestParam(required = false) String targetLanguage,
            HttpServletRequest request) {
        try {
            log.info("Connecting browser caller to conference: {}", conferenceName);

            // If no conference name provided, this might be a direct call
            if (conferenceName == null) {
                log.info("No conference specified - this might be a direct call setup");
                // Handle direct calling scenario
                conferenceName = "direct-" + UUID.randomUUID().toString();
                activeConferences.put(conferenceName, new ConferenceInfo(
                    sourceLanguage != null ? sourceLanguage : "en-US",
                    targetLanguage != null ? targetLanguage : "es-ES"
                ));
            }

            Say welcomeMessage = new Say.Builder("Welcome to the translation service. Connecting you now.")
                .voice(Say.Voice.ALICE)
                .language(Say.Language.EN)
                .build();

            Conference conferenceNoun = new Conference.Builder(conferenceName)
                .startConferenceOnEnter(true)
                .endConferenceOnExit(true)
                .record(Conference.Record.RECORD_FROM_START)
                .recordingStatusCallback("https://talktranslate-backend-production.up.railway.app/api/call/recording")
                .build();

            Dial dial = new Dial.Builder()
                .conference(conferenceNoun)
                .build();

            VoiceResponse response = new VoiceResponse.Builder()
                .say(welcomeMessage)
                .dial(dial)
                .build();

            return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_XML)
                .body(response.toXml());

        } catch (Exception e) {
            log.error("Error connecting caller to conference: {}", e.getMessage(), e);
            
            Say errorMessage = new Say.Builder("Sorry, there was an error. Please try again.")
                .voice(Say.Voice.ALICE)
                .build();

            VoiceResponse response = new VoiceResponse.Builder()
                .say(errorMessage)
                .build();

            return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_XML)
                .body(response.toXml());
        }
    }

    @PostMapping("/status")
    public ResponseEntity<String> handleCallStatus(HttpServletRequest request) {
        try {
            String callSid = request.getParameter("CallSid");
            String callStatus = request.getParameter("CallStatus");
            
            log.info("Call status update - SID: {}, Status: {}", callSid, callStatus);
            
            return ResponseEntity.ok("OK");
            
        } catch (Exception e) {
            log.error("Error handling call status: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("Error");
        }
    }

    @PostMapping("/recording")
    public ResponseEntity<String> handleRecording(HttpServletRequest request) {
        try {
            String recordingUrl = request.getParameter("RecordingUrl");
            String recordingSid = request.getParameter("RecordingSid");
            
            log.info("Recording available - SID: {}, URL: {}", recordingSid, recordingUrl);
            
            return ResponseEntity.ok("OK");
            
        } catch (Exception e) {
            log.error("Error handling recording: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("Error");
        }
    }

    @PostMapping("/end-conference")
    public ResponseEntity<Map<String, String>> endConference(@RequestBody Map<String, String> request) {
        try {
            String conferenceName = request.get("conferenceName");
            
            if (conferenceName != null) {
                activeConferences.remove(conferenceName);
                log.info("Conference ended and cleaned up: {}", conferenceName);
            }
            
            Map<String, String> response = new HashMap<>();
            response.put("success", "true");
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error ending conference: {}", e.getMessage(), e);
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }
}