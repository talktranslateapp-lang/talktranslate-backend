package com.example.translationcallapp.controller;

import com.example.translationcallapp.service.BotParticipantService;
import com.example.translationcallapp.service.OpenAITranslationService;
import com.twilio.Twilio;
import com.twilio.jwt.accesstoken.AccessToken;
import com.twilio.jwt.accesstoken.VoiceGrant;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.rest.api.v2010.account.Conference;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Dial;
import com.twilio.twiml.voice.Say;
import com.twilio.type.PhoneNumber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/api/calls")
@CrossOrigin(origins = "*", maxAge = 3600)
public class CallController {

    @Value("${twilio.account.sid}")
    private String accountSid;

    @Value("${twilio.auth.token}")
    private String authToken;

    @Value("${twilio.api.key}")
    private String apiKey;

    @Value("${twilio.api.secret}")
    private String apiSecret;

    @Value("${twilio.phone.number}")
    private String twilioPhoneNumber;

    @Value("${twilio.app.sid}")
    private String twimlAppSid;

    @Value("${app.base.url}")
    private String baseUrl;

    private final BotParticipantService botParticipantService;
    private final OpenAITranslationService translationService;
    private final Map<String, ConferenceSession> activeSessions = new ConcurrentHashMap<>();

    @Autowired
    public CallController(BotParticipantService botParticipantService,
                         OpenAITranslationService translationService) {
        this.botParticipantService = botParticipantService;
        this.translationService = translationService;
    }

    @PostConstruct
    private void initTwilio() {
        if (!Twilio.isInitialized()) {
            Twilio.init(accountSid, authToken);
            log.info("Twilio initialized successfully");
        }
    }

    @GetMapping("/token")
    public ResponseEntity<?> generateAccessToken(
            @RequestParam(value = "identity", required = false) String identity,
            @RequestParam(value = "room", required = false) String room) {
        
        try {
            if (identity == null || identity.trim().isEmpty()) {
                identity = "user_" + System.currentTimeMillis();
            }

            // Create access token
            AccessToken accessToken = new AccessToken.Builder(accountSid, apiKey, apiSecret)
                    .identity(identity)
                    .build();

            // Create voice grant
            VoiceGrant voiceGrant = new VoiceGrant();
            voiceGrant.setOutgoingApplicationSid(twimlAppSid);
            voiceGrant.setIncomingAllow(true);
            
            // Add the grant to the token
            accessToken.addGrant(voiceGrant);

            String token = accessToken.toJwt();
            log.info("Generated access token for identity: {}", identity);

            Map<String, Object> response = new HashMap<>();
            response.put("token", token);
            response.put("identity", identity);
            response.put("accountSid", accountSid);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error generating access token: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to generate token"));
        }
    }

    @PostMapping("/initiate")
    public ResponseEntity<?> initiateCall(
            @RequestParam("to") String toPhoneNumber,
            @RequestParam(value = "from", required = false) String fromPhoneNumber,
            @RequestParam(value = "conferenceId", required = false) String conferenceId,
            @RequestParam(value = "enableTranslation", defaultValue = "false") boolean enableTranslation,
            @RequestParam(value = "sourceLanguage", defaultValue = "en") String sourceLanguage,
            @RequestParam(value = "targetLanguage", defaultValue = "es") String targetLanguage) {
        
        try {
            log.info("Initiating call to: {} with translation: {}", toPhoneNumber, enableTranslation);

            if (fromPhoneNumber == null) {
                fromPhoneNumber = twilioPhoneNumber;
            }

            if (conferenceId == null) {
                conferenceId = "conf_" + System.currentTimeMillis();
            }

            // Create the call
            String callbackUrl = baseUrl + "/api/calls/handle?conferenceId=" + conferenceId +
                                "&enableTranslation=" + enableTranslation +
                                "&sourceLanguage=" + sourceLanguage +
                                "&targetLanguage=" + targetLanguage;

            Call call = Call.creator(
                    new PhoneNumber(toPhoneNumber),
                    new PhoneNumber(fromPhoneNumber),
                    URI.create(callbackUrl)
            )
            .setStatusCallback(URI.create(baseUrl + "/api/calls/status"))
            .setStatusCallbackEvent(Arrays.asList("initiated", "ringing", "answered", "completed"))
            .create();

            // Track the session
            ConferenceSession session = new ConferenceSession(
                    conferenceId, call.getSid(), enableTranslation, sourceLanguage, targetLanguage
            );
            activeSessions.put(conferenceId, session);

            log.info("Call initiated successfully - Call SID: {}, Conference: {}", call.getSid(), conferenceId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("callSid", call.getSid());
            response.put("conferenceId", conferenceId);
            response.put("status", call.getStatus().toString());
            
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error initiating call: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to initiate call: " + e.getMessage()));
        }
    }

    @PostMapping(value = "/handle", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> handleCall(
            @RequestParam(value = "conferenceId") String conferenceId,
            @RequestParam(value = "enableTranslation", defaultValue = "false") boolean enableTranslation,
            @RequestParam(value = "sourceLanguage", defaultValue = "en") String sourceLanguage,
            @RequestParam(value = "targetLanguage", defaultValue = "es") String targetLanguage,
            HttpServletRequest request,
            HttpServletResponse response) {
        
        try {
            log.info("Handling call for conference: {}", conferenceId);

            VoiceResponse.Builder responseBuilder = new VoiceResponse.Builder();

            // Welcome message
            Say welcomeMessage = new Say.Builder("Welcome to the translation service. You are joining the conference.")
                    .voice(Say.Voice.ALICE)
                    .language(Say.Language.EN)
                    .build();
            responseBuilder.say(welcomeMessage);

            // Configure conference
            com.twilio.twiml.voice.Conference.Builder conferenceBuilder = 
                    new com.twilio.twiml.voice.Conference.Builder(conferenceId)
                    .startConferenceOnEnter(true)
                    .endConferenceOnExit(false)
                    .waitUrl(URI.create(baseUrl + "/api/calls/wait-music"))
                    .statusCallback(URI.create(baseUrl + "/api/calls/conference-status"))
                    .statusCallbackEvent(Arrays.asList("start", "end", "join", "leave"));

            // Add recording if translation is enabled
            if (enableTranslation) {
                conferenceBuilder
                        .record(com.twilio.twiml.voice.Conference.Record.RECORD_FROM_START)
                        .recordingStatusCallback(URI.create(baseUrl + "/api/calls/recording-status"));
            }

            Dial dial = new Dial.Builder()
                    .conference(conferenceBuilder.build())
                    .build();

            VoiceResponse twimlResponse = responseBuilder.dial(dial).build();
            String twiml = twimlResponse.toXml();

            log.debug("Generated TwiML: {}", twiml);

            // Add translation bot if enabled
            if (enableTranslation) {
                addTranslationBot(conferenceId, sourceLanguage, targetLanguage);
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_XML)
                    .body(twiml);

        } catch (Exception e) {
            log.error("Error handling call: {}", e.getMessage(), e);
            
            // Return error TwiML
            VoiceResponse errorResponse = new VoiceResponse.Builder()
                    .say(new Say.Builder("Sorry, there was an error processing your call. Please try again.")
                            .voice(Say.Voice.ALICE)
                            .build())
                    .build();
            
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_XML)
                    .body(errorResponse.toXml());
        }
    }

    @PostMapping(value = "/wait-music", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> waitMusic(HttpServletResponse response) {
        try {
            VoiceResponse waitResponse = new VoiceResponse.Builder()
                    .say(new Say.Builder("Please wait while we connect you to the conference.")
                            .voice(Say.Voice.ALICE)
                            .loop(3)
                            .build())
                    .build();

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_XML)
                    .body(waitResponse.toXml());

        } catch (Exception e) {
            log.error("Error generating wait music: {}", e.getMessage(), e);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_XML)
                    .body("<Response></Response>");
        }
    }

    @PostMapping("/status")
    public ResponseEntity<?> handleCallStatus(
            @RequestParam("CallSid") String callSid,
            @RequestParam("CallStatus") String callStatus,
            @RequestParam(value = "CallDuration", required = false) String callDuration,
            HttpServletRequest request) {
        
        try {
            log.info("Call status update - SID: {}, Status: {}, Duration: {}", 
                    callSid, callStatus, callDuration);

            // Update session status
            activeSessions.values().forEach(session -> {
                if (callSid.equals(session.getCallSid())) {
                    session.setStatus(callStatus);
                    if (callDuration != null) {
                        session.setDuration(Integer.parseInt(callDuration));
                    }
                }
            });

            // Clean up completed calls
            if ("completed".equals(callStatus) || "failed".equals(callStatus) || "canceled".equals(callStatus)) {
                cleanupCall(callSid);
            }

            return ResponseEntity.ok(Map.of("received", true));

        } catch (Exception e) {
            log.error("Error handling call status: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of("received", false));
        }
    }

    @PostMapping("/conference-status")
    public ResponseEntity<?> handleConferenceStatus(
            @RequestParam("ConferenceSid") String conferenceSid,
            @RequestParam("StatusCallbackEvent") String event,
            @RequestParam(value = "FriendlyName", required = false) String friendlyName,
            HttpServletResponse response) {
        
        try {
            log.info("Conference status - SID: {}, Event: {}, Name: {}", 
                    conferenceSid, event, friendlyName);

            ConferenceSession session = findSessionByConference(friendlyName);
            if (session != null) {
                session.addEvent(event, System.currentTimeMillis());
                
                if ("end".equals(event)) {
                    cleanupConference(friendlyName);
                }
            }

            return ResponseEntity.ok(Map.of("received", true));

        } catch (Exception e) {
            log.error("Error handling conference status: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of("received", false));
        }
    }

    @PostMapping("/recording-status")
    public ResponseEntity<?> handleRecordingStatus(
            @RequestParam("RecordingSid") String recordingSid,
            @RequestParam("RecordingUrl") String recordingUrl,
            @RequestParam("RecordingStatus") String recordingStatus,
            @RequestParam(value = "ConferenceSid", required = false) String conferenceSid,
            HttpServletResponse response) {
        
        try {
            log.info("Recording status - SID: {}, Status: {}, URL: {}", 
                    recordingSid, recordingStatus, recordingUrl);

            if ("completed".equals(recordingStatus)) {
                // Process recording for translation
                processRecordingForTranslation(recordingSid, recordingUrl, conferenceSid);
            }

            return ResponseEntity.ok(Map.of("received", true));

        } catch (Exception e) {
            log.error("Error handling recording status: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of("received", false));
        }
    }

    @GetMapping("/conferences")
    public ResponseEntity<?> listActiveConferences() {
        try {
            List<Map<String, Object>> conferences = new ArrayList<>();
            
            for (ConferenceSession session : activeSessions.values()) {
                Map<String, Object> conf = new HashMap<>();
                conf.put("conferenceId", session.getConferenceId());
                conf.put("callSid", session.getCallSid());
                conf.put("status", session.getStatus());
                conf.put("translationEnabled", session.isTranslationEnabled());
                conf.put("sourceLanguage", session.getSourceLanguage());
                conf.put("targetLanguage", session.getTargetLanguage());
                conf.put("createdAt", session.getCreatedAt().toString());
                conf.put("events", session.getEvents());
                conferences.add(conf);
            }

            return ResponseEntity.ok(Map.of("conferences", conferences));

        } catch (Exception e) {
            log.error("Error listing conferences: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to list conferences"));
        }
    }

    @PostMapping("/conferences/{conferenceId}/end")
    public ResponseEntity<?> endConference(@PathVariable String conferenceId) {
        try {
            log.info("Ending conference: {}", conferenceId);

            // End the conference
            Conference conference = Conference.fetcher(conferenceId).fetch();
            if (conference != null) {
                Conference.updater(conferenceId)
                        .setStatus(Conference.UpdateStatus.COMPLETED)
                        .update();
            }

            // Remove bots
            botParticipantService.removeAllBotsFromConference(conferenceId);

            // Clean up session
            activeSessions.remove(conferenceId);

            return ResponseEntity.ok(Map.of("success", true, "message", "Conference ended"));

        } catch (Exception e) {
            log.error("Error ending conference {}: {}", conferenceId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to end conference"));
        }
    }

    private void addTranslationBot(String conferenceId, String sourceLanguage, String targetLanguage) {
        try {
            Map<String, String> botConfig = new HashMap<>();
            botConfig.put("sourceLanguage", sourceLanguage);
            botConfig.put("targetLanguage", targetLanguage);
            botConfig.put("mediaStreamUrl", baseUrl + "/media-stream");

            String botId = botParticipantService.addBotToConference(conferenceId, "translator", botConfig);
            log.info("Translation bot added to conference {} with ID: {}", conferenceId, botId);

        } catch (Exception e) {
            log.error("Failed to add translation bot to conference {}: {}", conferenceId, e.getMessage(), e);
        }
    }

    private void processRecordingForTranslation(String recordingSid, String recordingUrl, String conferenceSid) {
        try {
            log.info("Processing recording for translation: {}", recordingSid);
            
            // This would typically download the recording and process it
            // For now, just log the event
            // TODO: Implement actual recording processing with OpenAI
            
        } catch (Exception e) {
            log.error("Error processing recording {}: {}", recordingSid, e.getMessage(), e);
        }
    }

    private ConferenceSession findSessionByConference(String conferenceId) {
        return activeSessions.get(conferenceId);
    }

    private void cleanupCall(String callSid) {
        try {
            // Remove sessions associated with this call
            activeSessions.entrySet().removeIf(entry -> callSid.equals(entry.getValue().getCallSid()));
            log.info("Cleaned up call: {}", callSid);
        } catch (Exception e) {
            log.error("Error cleaning up call {}: {}", callSid, e.getMessage());
        }
    }

    private void cleanupConference(String conferenceId) {
        try {
            // Remove bots
            botParticipantService.removeAllBotsFromConference(conferenceId);
            
            // Remove session
            activeSessions.remove(conferenceId);
            
            log.info("Cleaned up conference: {}", conferenceId);
        } catch (Exception e) {
            log.error("Error cleaning up conference {}: {}", conferenceId, e.getMessage());
        }
    }

    // Inner class to track conference sessions
    private static class ConferenceSession {
        private final String conferenceId;
        private final String callSid;
        private final boolean translationEnabled;
        private final String sourceLanguage;
        private final String targetLanguage;
        private final java.time.LocalDateTime createdAt;
        private String status;
        private int duration;
        private final List<Map<String, Object>> events;

        public ConferenceSession(String conferenceId, String callSid, boolean translationEnabled,
                               String sourceLanguage, String targetLanguage) {
            this.conferenceId = conferenceId;
            this.callSid = callSid;
            this.translationEnabled = translationEnabled;
            this.sourceLanguage = sourceLanguage;
            this.targetLanguage = targetLanguage;
            this.createdAt = java.time.LocalDateTime.now();
            this.status = "initiated";
            this.duration = 0;
            this.events = new ArrayList<>();
        }

        public void addEvent(String event, long timestamp) {
            Map<String, Object> eventMap = new HashMap<>();
            eventMap.put("event", event);
            eventMap.put("timestamp", timestamp);
            this.events.add(eventMap);
        }

        // Getters and setters
        public String getConferenceId() { return conferenceId; }
        public String getCallSid() { return callSid; }
        public boolean isTranslationEnabled() { return translationEnabled; }
        public String getSourceLanguage() { return sourceLanguage; }
        public String getTargetLanguage() { return targetLanguage; }
        public java.time.LocalDateTime getCreatedAt() { return createdAt; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getDuration() { return duration; }
        public void setDuration(int duration) { this.duration = duration; }
        public List<Map<String, Object>> getEvents() { return events; }
    }
}