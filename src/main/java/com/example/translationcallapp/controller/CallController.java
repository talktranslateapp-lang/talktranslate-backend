package com.example.translationcallapp.controller;

import com.example.translationcallapp.service.BotParticipantService;
import com.twilio.jwt.accesstoken.AccessToken;
import com.twilio.jwt.accesstoken.VoiceGrant;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.*;
import com.twilio.type.PhoneNumber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/call")
@CrossOrigin
@EnableScheduling
@Slf4j
public class CallController {

    @Value("${twilio.account.sid}")
    private String twilioAccountSid;

    @Value("${twilio.api.key}")
    private String twilioApiKey;

    @Value("${twilio.api.secret}")
    private String twilioApiSecret;

    @Value("${twilio.twiml.app.sid}")
    private String twilioTwimlAppSid;

    @Value("${twilio.phone.number}")
    private String twilioPhoneNumber;

    @Value("${app.conference.session-timeout-minutes:30}")
    private int sessionTimeoutMinutes;

    @Autowired
    private BotParticipantService botParticipantService;

    // Store conference session data
    private final Map<String, ConferenceSession> conferenceSessions = new ConcurrentHashMap<>();

    @GetMapping("/token")
    public ResponseEntity<Map<String, String>> generateToken(@RequestParam String identity) {
        try {
            AccessToken accessToken = new AccessToken.Builder(
                    twilioAccountSid,
                    twilioApiKey,
                    twilioApiSecret
            ).identity(identity).build();

            VoiceGrant voiceGrant = new VoiceGrant();
            voiceGrant.setOutgoingApplicationSid(twilioTwimlAppSid);
            voiceGrant.setIncomingAllow(true);
            accessToken.addGrant(voiceGrant);

            Map<String, String> response = new HashMap<>();
            response.put("token", accessToken.toJwt());
            response.put("identity", identity);

            log.info("Generated token for identity: {}", identity);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response);

        } catch (Exception e) {
            log.error("Error generating token for identity: {}", identity, e);
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to generate token");
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    @PostMapping("/initiate-call")
    public ResponseEntity<Map<String, Object>> initiateCall(@RequestBody Map<String, String> request) {
        try {
            String targetPhoneNumber = request.get("phoneNumber");
            String sourceLanguage = request.get("sourceLanguage");
            String targetLanguage = request.get("targetLanguage");
            
            // Input validation using Spring's StringUtils
            if (!StringUtils.hasText(targetPhoneNumber) || 
                !StringUtils.hasText(sourceLanguage) || 
                !StringUtils.hasText(targetLanguage)) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Missing required parameters: phoneNumber, sourceLanguage, targetLanguage");
                return ResponseEntity.badRequest().body(error);
            }
            
            // Generate unique conference name
            String conferenceName = "translation-call-" + System.currentTimeMillis();
            
            log.info("Initiating translation call - Conference: {}, Target: {}, Languages: {} -> {}", 
                    conferenceName, targetPhoneNumber, sourceLanguage, targetLanguage);

            // Store conference session with timestamp
            ConferenceSession session = new ConferenceSession(
                    conferenceName, targetPhoneNumber, sourceLanguage, targetLanguage);
            conferenceSessions.put(conferenceName, session);

            // Create call to target phone number with properly encoded parameters
            String targetWebhookUrl = "https://talktranslate-backend-production.up.railway.app/api/call/connect-target" +
                    "?conferenceName=" + URLEncoder.encode(conferenceName, StandardCharsets.UTF_8);
            
            Call targetCall = Call.creator(
                    new PhoneNumber(targetPhoneNumber),
                    new PhoneNumber(twilioPhoneNumber),
                    URI.create(targetWebhookUrl)
            ).create();

            session.setTargetCallSid(targetCall.getSid());
            log.info("Target call created: {}", targetCall.getSid());

            // Add bot participant to conference for translation audio injection
            String botCallSid = botParticipantService.addBotToConference(conferenceName);
            session.setBotCallSid(botCallSid);

            Map<String, Object> response = new HashMap<>();
            response.put("conferenceName", conferenceName);
            response.put("targetCallSid", targetCall.getSid());
            response.put("botCallSid", botCallSid);
            response.put("status", "initiated");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error initiating call", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to initiate call"); // Generic error for security
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    @PostMapping("/connect-caller")
    public void connectCaller(@RequestParam String conferenceName, 
                             HttpServletResponse response) throws IOException {
        try {
            log.info("Connecting browser caller to conference: {}", conferenceName);

            ConferenceSession session = conferenceSessions.get(conferenceName);
            if (session == null) {
                log.error("Conference session not found: {}", conferenceName);
                response.setStatus(404);
                return;
            }

            // Build WebSocket URL with proper parameter encoding
            String mediaStreamUrl = "wss://talktranslate-backend-production.up.railway.app/media-stream" +
                    "?conferenceName=" + URLEncoder.encode(conferenceName, StandardCharsets.UTF_8) +
                    "&participant=" + URLEncoder.encode("caller", StandardCharsets.UTF_8) +
                    "&sourceLanguage=" + URLEncoder.encode(session.getSourceLanguage(), StandardCharsets.UTF_8) +
                    "&targetLanguage=" + URLEncoder.encode(session.getTargetLanguage(), StandardCharsets.UTF_8);

            // TwiML to connect caller to conference with Media Streams
            VoiceResponse twiml = new VoiceResponse.Builder()
                    .say(new Say.Builder("Welcome to the translation service. Connecting you now.")
                            .voice(Say.Voice.ALICE)
                            .build())
                    .start(new Start.Builder()
                            .stream(new Stream.Builder()
                                    .url(mediaStreamUrl)
                                    .track(Stream.Track.BOTH_TRACKS)
                                    .build())
                            .build())
                    .dial(new Dial.Builder()
                            .conference(new Conference.Builder(conferenceName)
                                    .record(Conference.Record.RECORD_FROM_START)
                                    .build())
                            .build())
                    .build();

            response.setContentType("text/xml");
            response.getWriter().write(twiml.toXml());
            log.info("Caller connected to conference: {}", conferenceName);

        } catch (Exception e) {
            log.error("Error connecting caller to conference: {}", conferenceName, e);
            response.setStatus(500);
        }
    }

    @PostMapping("/connect-target")
    public void connectTarget(@RequestParam String conferenceName, 
                             HttpServletResponse response) throws IOException {
        try {
            log.info("Connecting target phone to conference: {}", conferenceName);

            ConferenceSession session = conferenceSessions.get(conferenceName);
            if (session == null) {
                log.error("Conference session not found: {}", conferenceName);
                response.setStatus(404);
                return;
            }

            // Build WebSocket URL with proper parameter encoding
            String mediaStreamUrl = "wss://talktranslate-backend-production.up.railway.app/media-stream" +
                    "?conferenceName=" + URLEncoder.encode(conferenceName, StandardCharsets.UTF_8) +
                    "&participant=" + URLEncoder.encode("target", StandardCharsets.UTF_8) +
                    "&sourceLanguage=" + URLEncoder.encode(session.getTargetLanguage(), StandardCharsets.UTF_8) +
                    "&targetLanguage=" + URLEncoder.encode(session.getSourceLanguage(), StandardCharsets.UTF_8);

            // TwiML to connect target to conference with Media Streams
            VoiceResponse twiml = new VoiceResponse.Builder()
                    .say(new Say.Builder("You have a translated call. Please hold while we connect you.")
                            .voice(Say.Voice.ALICE)
                            .build())
                    .start(new Start.Builder()
                            .stream(new Stream.Builder()
                                    .url(mediaStreamUrl)
                                    .track(Stream.Track.BOTH_TRACKS)
                                    .build())
                            .build())
                    .dial(new Dial.Builder()
                            .conference(new Conference.Builder(conferenceName)
                                    .record(Conference.Record.RECORD_FROM_START)
                                    .build())
                            .build())
                    .build();

            response.setContentType("text/xml");
            response.getWriter().write(twiml.toXml());
            log.info("Target connected to conference: {}", conferenceName);

        } catch (Exception e) {
            log.error("Error connecting target to conference: {}", conferenceName, e);
            response.setStatus(500);
        }
    }

    @PostMapping("/bot-join")
    public void botJoinConference(@RequestParam String conferenceName, 
                                 @RequestParam(required = false) String muted,
                                 HttpServletRequest request,
                                 HttpServletResponse response) throws IOException {
        try {
            log.info("Bot joining conference: {}, Muted: {}", conferenceName, muted);

            boolean shouldMute = !"false".equals(muted); // Default to muted unless explicitly false

            VoiceResponse twiml = new VoiceResponse.Builder()
                    .dial(new Dial.Builder()
                            .conference(new Conference.Builder(conferenceName)
                                    .muted(shouldMute)
                                    .statusCallback("https://talktranslate-backend-production.up.railway.app/api/call/conference-status")
                                    .statusCallbackEvent("join leave")
                                    .statusCallbackMethod("POST")
                                    .build())
                            .build())
                    .build();

            response.setContentType("text/xml");
            response.getWriter().write(twiml.toXml());
            log.info("Bot joined conference: {}, Muted: {}", conferenceName, shouldMute);

        } catch (Exception e) {
            log.error("Error with bot joining conference: {}", conferenceName, e);
            response.setStatus(500);
        }
    }

    @PostMapping("/bot-play")
    public void botPlayAudio(@RequestParam String audioUrl, 
                            @RequestParam String conferenceName,
                            HttpServletResponse response) throws IOException {
        try {
            log.info("Bot playing translated audio: {}", audioUrl);

            // Simple TwiML - play audio then return to muted state
            VoiceResponse twiml = new VoiceResponse.Builder()
                    .play(new Play.Builder(audioUrl).build())
                    .redirect(new Redirect.Builder(
                            "https://talktranslate-backend-production.up.railway.app/api/call/bot-mute" +
                            "?conferenceName=" + URLEncoder.encode(conferenceName, StandardCharsets.UTF_8))
                            .method(Redirect.Method.POST)
                            .build())
                    .build();

            response.setContentType("text/xml");
            response.getWriter().write(twiml.toXml());
            log.info("Bot playing audio and will return to muted state");

        } catch (Exception e) {
            log.error("Error with bot playing audio", e);
            response.setStatus(500);
        }
    }

    @PostMapping("/bot-mute")
    public void botMuteInConference(@RequestParam String conferenceName, 
                                   HttpServletResponse response) throws IOException {
        try {
            log.info("Bot returning to muted state in conference: {}", conferenceName);

            // TwiML to keep bot in conference but muted
            VoiceResponse twiml = new VoiceResponse.Builder()
                    .dial(new Dial.Builder()
                            .conference(new Conference.Builder(conferenceName)
                                    .muted(true)
                                    .build())
                            .build())
                    .build();

            response.setContentType("text/xml");
            response.getWriter().write(twiml.toXml());
            log.info("Bot muted in conference: {}", conferenceName);

        } catch (Exception e) {
            log.error("Error muting bot in conference: {}", conferenceName, e);
            response.setStatus(500);
        }
    }

    @PostMapping("/conference-status")
    public void conferenceStatusCallback(@RequestParam String ConferenceSid,
                                       @RequestParam String CallSid,
                                       @RequestParam String StatusCallbackEvent,
                                       @RequestParam(required = false) String ParticipantSid,
                                       HttpServletResponse response) throws IOException {
        try {
            log.info("Conference status - Event: {}, Conference: {}, Call: {}, Participant: {}", 
                    StatusCallbackEvent, ConferenceSid, CallSid, ParticipantSid);

            // Check if this is our bot joining the conference
            String conferenceName = findConferenceNameByCallSid(CallSid);
            if (conferenceName != null && "participant-join".equals(StatusCallbackEvent)) {
                // Store participant SID for bot control
                botParticipantService.setBotParticipantSid(conferenceName, ParticipantSid);
                log.info("Stored bot participant SID for conference: {}", conferenceName);
            }

            response.setStatus(200);
            response.getWriter().write("OK");

        } catch (Exception e) {
            log.error("Error processing conference status callback", e);
            response.setStatus(500);
        }
    }

    @PostMapping("/conference/{conferenceName}/play-translation")
    public ResponseEntity<Map<String, Object>> playTranslationInConference(@PathVariable String conferenceName,
                                                                           @RequestBody Map<String, String> request) {
        try {
            String audioUrl = request.get("audioUrl");
            
            // Use Spring's StringUtils for better validation
            if (!StringUtils.hasText(audioUrl)) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Audio URL is required");
                error.put("conferenceName", conferenceName);
                return ResponseEntity.badRequest().body(error);
            }

            log.info("Playing translation in conference: {}, Audio: {}", conferenceName, audioUrl);
            
            // Check if bot exists
            if (!botParticipantService.hasBotInConference(conferenceName)) {
                Map<String, Object> error = new HashMap<>();
                error.put("error", "Conference not found or no bot available");
                error.put("conferenceName", conferenceName);
                return ResponseEntity.notFound().body(error);
            }

            // Use bot service to play audio
            boolean success = botParticipantService.playTranslatedAudio(conferenceName, audioUrl);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", success);
            response.put("conferenceName", conferenceName);
            response.put("message", success ? "Translation audio queued for playback" : "Failed to queue audio");
            
            return success ? ResponseEntity.ok(response) : ResponseEntity.status(500).body(response);

        } catch (Exception e) {
            log.error("Error playing translation in conference: {}", conferenceName, e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Internal server error"); // Generic error for security
            error.put("conferenceName", conferenceName);
            return ResponseEntity.status(500).body(error);
        }
    }

    @PostMapping("/conference/{conferenceName}/end")
    public ResponseEntity<String> endConference(@PathVariable String conferenceName) {
        try {
            log.info("Ending conference: {}", conferenceName);
            
            // Remove bot from conference
            botParticipantService.removeBotFromConference(conferenceName);
            
            // Clean up session data
            conferenceSessions.remove(conferenceName);
            
            return ResponseEntity.ok("Conference ended");

        } catch (Exception e) {
            log.error("Error ending conference: {}", conferenceName, e);
            return ResponseEntity.status(500).body("Failed to end conference");
        }
    }

    @GetMapping("/conference/{conferenceName}/session")
    public ResponseEntity<ConferenceSession> getConferenceSession(@PathVariable String conferenceName) {
        ConferenceSession session = conferenceSessions.get(conferenceName);
        if (session != null) {
            return ResponseEntity.ok(session);
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * Get session count for monitoring
     */
    @GetMapping("/sessions/count")
    public ResponseEntity<Map<String, Object>> getSessionCount() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("activeSessions", conferenceSessions.size());
        stats.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(stats);
    }

    /**
     * Manual cleanup endpoint for operations
     */
    @PostMapping("/sessions/cleanup")
    public ResponseEntity<Map<String, Object>> manualCleanup() {
        try {
            int cleaned = performSessionCleanup(true); // Force cleanup
            
            Map<String, Object> result = new HashMap<>();
            result.put("cleanedSessions", cleaned);
            result.put("remainingSessions", conferenceSessions.size());
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("Error during manual cleanup", e);
            return ResponseEntity.status(500).body(Map.of("error", "Cleanup failed"));
        }
    }

    /**
     * Scheduled cleanup of orphaned sessions every 10 minutes
     */
    @Scheduled(fixedRate = 600000) // 10 minutes in milliseconds
    public void cleanupOrphanedSessions() {
        try {
            log.debug("Starting scheduled cleanup of orphaned conference sessions");
            
            int cleaned = performSessionCleanup(false); // Normal cleanup
            
            if (cleaned > 0) {
                log.info("Scheduled cleanup removed {} orphaned sessions", cleaned);
            }
            
        } catch (Exception e) {
            log.error("Error during scheduled session cleanup", e);
        }
    }

    /**
     * Perform session cleanup logic
     */
    private int performSessionCleanup(boolean forceCleanup) {
        long currentTime = System.currentTimeMillis();
        long timeoutMs = sessionTimeoutMinutes * 60 * 1000L;
        
        int cleaned = 0;
        
        // Create a copy of session names to avoid concurrent modification
        var sessionNames = conferenceSessions.keySet().toArray(new String[0]);
        
        for (String conferenceName : sessionNames) {
            ConferenceSession session = conferenceSessions.get(conferenceName);
            if (session == null) continue;
            
            boolean shouldClean = false;
            
            if (forceCleanup) {
                // Force cleanup: remove if no bot exists
                shouldClean = !botParticipantService.hasBotInConference(conferenceName);
            } else {
                // Normal cleanup: remove if session is old and no bot exists
                boolean isOld = (currentTime - session.getCreatedTime()) > timeoutMs;
                boolean noBotExists = !botParticipantService.hasBotInConference(conferenceName);
                shouldClean = isOld && noBotExists;
            }
            
            if (shouldClean) {
                conferenceSessions.remove(conferenceName);
                // Also try to clean up any remaining bot
                botParticipantService.removeBotFromConference(conferenceName);
                cleaned++;
                log.info("Cleaned up {} session: {}", forceCleanup ? "forced" : "orphaned", conferenceName);
            }
        }
        
        return cleaned;
    }

    /**
     * Helper method to find conference name by call SID
     */
    private String findConferenceNameByCallSid(String callSid) {
        for (Map.Entry<String, ConferenceSession> entry : conferenceSessions.entrySet()) {
            ConferenceSession session = entry.getValue();
            if (callSid.equals(session.getTargetCallSid()) || 
                callSid.equals(session.getBotCallSid()) ||
                callSid.equals(botParticipantService.getBotCallSid(entry.getKey()))) {
                return entry.getKey();
            }
        }
        return null;
    }

    // Enhanced inner class for conference session management with timestamp
    public static class ConferenceSession {
        private String conferenceName;
        private String targetPhoneNumber;
        private String sourceLanguage;
        private String targetLanguage;
        private String targetCallSid;
        private String botCallSid;
        private long createdTime;

        public ConferenceSession(String conferenceName, String targetPhoneNumber, 
                               String sourceLanguage, String targetLanguage) {
            this.conferenceName = conferenceName;
            this.targetPhoneNumber = targetPhoneNumber;
            this.sourceLanguage = sourceLanguage;
            this.targetLanguage = targetLanguage;
            this.createdTime = System.currentTimeMillis();
        }

        // Getters and setters
        public String getConferenceName() { return conferenceName; }
        public String getTargetPhoneNumber() { return targetPhoneNumber; }
        public String getSourceLanguage() { return sourceLanguage; }
        public String getTargetLanguage() { return targetLanguage; }
        public String getTargetCallSid() { return targetCallSid; }
        public void setTargetCallSid(String targetCallSid) { this.targetCallSid = targetCallSid; }
        public String getBotCallSid() { return botCallSid; }
        public void setBotCallSid(String botCallSid) { this.botCallSid = botCallSid; }
        public long getCreatedTime() { return createdTime; }
    }
}