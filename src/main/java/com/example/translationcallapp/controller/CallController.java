package com.example.translationcallapp.controller;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.type.PhoneNumber;
import com.twilio.jwt.accesstoken.AccessToken;
import com.twilio.jwt.accesstoken.VoiceGrant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class CallController {
    
    @Value("${twilio.account.sid:}")
    private String accountSid;
    
    @Value("${twilio.auth.token:}")
    private String authToken;
    
    @Value("${twilio.phone.number:}")
    private String twilioPhoneNumber;
    
    @Value("${twilio.api.key:}")
    private String apiKey;
    
    @Value("${twilio.api.secret:}")
    private String apiSecret;
    
    @Value("${twilio.twiml.app.sid:}")
    private String twimlAppSid;
    
    @Value("${app.base.url:https://talktranslate-backend-production.up.railway.app}")
    private String baseUrl;
    
    private void initTwilio() {
        if (!accountSid.isEmpty() && !authToken.isEmpty()) {
            Twilio.init(accountSid, authToken);
        }
    }
    
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "OK");
        response.put("service", "Translation Call App");
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/generate-token")
    public ResponseEntity<Map<String, String>> generateToken(@RequestParam String identity) {
        Map<String, String> response = new HashMap<>();
        
        try {
            if (apiKey.isEmpty() || apiSecret.isEmpty()) {
                response.put("error", "Twilio API credentials not configured");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
            }
            
            // Create access token for Twilio Client SDK
            AccessToken accessToken = new AccessToken.Builder(
                accountSid,
                apiKey,
                apiSecret
            )
            .identity(identity)
            .build();
            
            // Add Voice Grant for making calls
            VoiceGrant voiceGrant = new VoiceGrant();
            if (!twimlAppSid.isEmpty()) {
                voiceGrant.setOutgoingApplicationSid(twimlAppSid);
            }
            voiceGrant.setIncomingAllow(true);
            accessToken.addGrant(voiceGrant);
            
            response.put("token", accessToken.toJwt());
            response.put("identity", identity);
            
            System.out.println("Generated access token for identity: " + identity);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            System.err.println("Failed to generate access token: " + e.getMessage());
            response.put("error", "Failed to generate token: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    
    @PostMapping("/start-call")
    public ResponseEntity<Map<String, Object>> startCall(
            @RequestParam(required = false) String phoneNumber,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false, defaultValue = "en") String fromLanguage,
            @RequestParam(required = false) String sourceLanguage,
            @RequestParam(required = false, defaultValue = "es") String toLanguage,
            @RequestParam(required = false) String targetLanguage) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            System.out.println("=== START CALL REQUEST (Web-based) ===");
            System.out.println("phoneNumber: " + phoneNumber);
            System.out.println("fromLanguage: " + fromLanguage);
            System.out.println("toLanguage: " + toLanguage);
            System.out.println("=== END DEBUG ===");
            
            String targetNumber = phoneNumber;
            if (targetNumber == null || targetNumber.isEmpty()) targetNumber = to;
            if (targetNumber == null || targetNumber.isEmpty()) targetNumber = phone;
            
            String sourceLang = (fromLanguage != null && !fromLanguage.isEmpty()) ? fromLanguage : sourceLanguage;
            String targetLang = (toLanguage != null && !toLanguage.isEmpty()) ? toLanguage : targetLanguage;
            
            if (sourceLang == null) sourceLang = "en";
            if (targetLang == null) targetLang = "es";
            
            sourceLang = convertLanguageCode(sourceLang);
            targetLang = convertLanguageCode(targetLang);
            
            if (targetNumber == null || targetNumber.trim().isEmpty()) {
                response.put("status", "error");
                response.put("message", "Phone number is required");
                return ResponseEntity.badRequest().body(response);
            }
            
            // Generate unique conference ID
            String conferenceId = "translation-" + System.currentTimeMillis();
            
            response.put("status", "success");
            response.put("message", "Conference ready for web-based calling");
            response.put("conferenceId", conferenceId);
            response.put("to", targetNumber);
            response.put("sourceLanguage", sourceLang);
            response.put("targetLanguage", targetLang);
            response.put("instructions", "Use Twilio Client SDK to join conference: " + conferenceId);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            System.out.println("Error in start-call: " + e.getMessage());
            response.put("status", "error");
            response.put("message", "Failed to prepare call: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    
    @PostMapping(value = "/voice", produces = MediaType.APPLICATION_XML_VALUE)
    public String handleVoiceWebhook(
            @RequestParam(required = false) String To,
            @RequestParam(required = false) String From,
            @RequestParam(required = false) String sourceLanguage,
            @RequestParam(required = false) String targetLanguage) {
        
        try {
            System.out.println("Voice webhook for web call - To: " + To + ", From: " + From + 
                             ", Source: " + sourceLanguage + ", Target: " + targetLanguage);
            
            // Generate conference ID based on call parameters
            String conferenceId = "translation-" + System.currentTimeMillis();
            
            StringBuilder twiml = new StringBuilder();
            twiml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            twiml.append("<Response>");
            
            // Start media streaming for translation
            if (sourceLanguage != null && targetLanguage != null) {
                String streamUrl = baseUrl.replace("https://", "wss://") + "/stream?source=" + 
                                 sourceLanguage + "&target=" + targetLanguage;
                twiml.append("<Start>");
                twiml.append("<Stream url=\"").append(streamUrl).append("\" />");
                twiml.append("</Start>");
            }
            
            twiml.append("<Say voice=\"alice\">");
            twiml.append("Welcome to the translation service. Joining conference now.");
            twiml.append("</Say>");
            
            // Join conference
            twiml.append("<Dial>");
            twiml.append("<Conference statusCallback=\"").append(baseUrl).append("/api/conference-status\">");
            twiml.append(conferenceId);
            twiml.append("</Conference>");
            twiml.append("</Dial>");
            
            twiml.append("</Response>");
            
            // Invite the phone participant to the same conference
            if (To != null && !To.isEmpty()) {
                inviteToConference(To, conferenceId, targetLanguage, sourceLanguage);
            }
            
            System.out.println("Generated web call TwiML: " + twiml.toString());
            
            return twiml.toString();
            
        } catch (Exception e) {
            System.out.println("Error in voice webhook: " + e.getMessage());
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Say voice=\"alice\">Conference setup failed. Please try again.</Say></Response>";
        }
    }
    
    @PostMapping(value = "/conference-invite", produces = MediaType.APPLICATION_XML_VALUE)
    public String handleConferenceInvite(
            @RequestParam String conference,
            @RequestParam(required = false, defaultValue = "en-US") String source,
            @RequestParam(required = false, defaultValue = "es-ES") String target) {
        
        try {
            System.out.println("Conference invite - Conference: " + conference + ", Source: " + source + ", Target: " + target);
            
            StringBuilder twiml = new StringBuilder();
            twiml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            twiml.append("<Response>");
            
            // Add media streaming for phone participant
            String streamUrl = baseUrl.replace("https://", "wss://") + "/stream?source=" + source + "&target=" + target;
            twiml.append("<Start>");
            twiml.append("<Stream url=\"").append(streamUrl).append("\" />");
            twiml.append("</Start>");
            
            twiml.append("<Say voice=\"alice\">");
            twiml.append("You have been invited to a translation call. Joining conference now.");
            twiml.append("</Say>");
            
            twiml.append("<Dial>");
            twiml.append("<Conference>").append(conference).append("</Conference>");
            twiml.append("</Dial>");
            
            twiml.append("</Response>");
            
            System.out.println("Generated invite TwiML: " + twiml.toString());
            
            return twiml.toString();
            
        } catch (Exception e) {
            System.out.println("Error in conference invite: " + e.getMessage());
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Say voice=\"alice\">Unable to join conference. Please try again.</Say></Response>";
        }
    }
    
    @PostMapping("/conference-status")
    public ResponseEntity<String> handleConferenceStatus(
            @RequestParam String ConferenceSid,
            @RequestParam String StatusCallbackEvent,
            @RequestParam(required = false) String ParticipantSid) {
        
        System.out.println("Conference Status - Conference: " + ConferenceSid + 
                          ", Event: " + StatusCallbackEvent + 
                          ", Participant: " + ParticipantSid);
        
        return ResponseEntity.ok("OK");
    }
    
    private void inviteToConference(String targetNumber, String conferenceId, String source, String target) {
        try {
            initTwilio();
            
            String cleanNumber = targetNumber.startsWith("+") ? targetNumber.substring(1) : targetNumber;
            String inviteTwimlUrl = baseUrl + "/api/conference-invite?conference=" + conferenceId + 
                                   "&source=" + source + "&target=" + target;
            
            // Small delay to ensure web participant joins first
            Thread.sleep(3000);
            
            Call.creator(
                new PhoneNumber("+" + cleanNumber),
                new PhoneNumber(twilioPhoneNumber),
                URI.create(inviteTwimlUrl)
            ).create();
            
            System.out.println("Invited +" + cleanNumber + " to conference: " + conferenceId);
            
        } catch (Exception e) {
            System.err.println("Failed to invite participant to conference: " + e.getMessage());
        }
    }
    
    @GetMapping("/call-status/{callSid}")
    public ResponseEntity<Map<String, Object>> getCallStatus(@PathVariable String callSid) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            if (accountSid.isEmpty() || authToken.isEmpty()) {
                response.put("status", "error");
                response.put("message", "Twilio credentials not configured");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
            }
            
            initTwilio();
            Call call = Call.fetcher(callSid).fetch();
            
            response.put("status", "success");
            response.put("callSid", call.getSid());
            response.put("callStatus", call.getStatus().toString());
            response.put("duration", call.getDuration());
            response.put("startTime", call.getStartTime());
            response.put("endTime", call.getEndTime());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Failed to fetch call status: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    
    @PostMapping("/end-call/{callSid}")
    public ResponseEntity<Map<String, String>> endCall(@PathVariable String callSid) {
        Map<String, String> response = new HashMap<>();
        
        try {
            if (accountSid.isEmpty() || authToken.isEmpty()) {
                response.put("status", "error");
                response.put("message", "Twilio credentials not configured");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
            }
            
            initTwilio();
            Call call = Call.updater(callSid)
                .setStatus(Call.UpdateStatus.COMPLETED)
                .update();
            
            response.put("status", "success");
            response.put("message", "Call ended successfully");
            response.put("callSid", call.getSid());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Failed to end call: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    
    private String convertLanguageCode(String languageCode) {
        if (languageCode == null) return "en-US";
        
        switch (languageCode.toLowerCase()) {
            case "en": return "en-US";
            case "es": return "es-ES";
            case "fr": return "fr-FR";
            case "de": return "de-DE";
            case "it": return "it-IT";
            case "pt": return "pt-BR";
            case "ja": return "ja-JP";
            case "ko": return "ko-KR";
            case "zh": return "zh-CN";
            case "ar": return "ar-SA";
            case "ru": return "ru-RU";
            case "hi": return "hi-IN";
            default: return languageCode;
        }
    }
    
    private String getLanguageName(String languageCode) {
        switch (languageCode) {
            case "en-US": return "English";
            case "es-ES": return "Spanish";
            case "fr-FR": return "French";
            case "de-DE": return "German";
            case "it-IT": return "Italian";
            case "pt-BR": return "Portuguese";
            case "ja-JP": return "Japanese";
            case "ko-KR": return "Korean";
            case "zh-CN": return "Chinese";
            case "ar-SA": return "Arabic";
            case "ru-RU": return "Russian";
            case "hi-IN": return "Hindi";
            default: return languageCode;
        }
    }
    
    private boolean isValidPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return false;
        }
        
        String cleaned = phoneNumber.replaceAll("[^+\\d]", "");
        
        if (!cleaned.startsWith("+")) {
            cleaned = "+1" + cleaned;
        }
        
        return cleaned.matches("\\+\\d{10,15}");
    }
}
