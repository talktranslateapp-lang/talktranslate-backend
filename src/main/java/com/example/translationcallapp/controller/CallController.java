package com.example.translationcallapp.controller;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.type.PhoneNumber;
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
            System.out.println("=== START CALL REQUEST DEBUG ===");
            System.out.println("phoneNumber: " + phoneNumber);
            System.out.println("to: " + to);
            System.out.println("phone: " + phone);
            System.out.println("fromLanguage: " + fromLanguage);
            System.out.println("sourceLanguage: " + sourceLanguage);
            System.out.println("toLanguage: " + toLanguage);
            System.out.println("targetLanguage: " + targetLanguage);
            System.out.println("=== END DEBUG ===");
            
            // Try to get phone number from any parameter name
            String targetNumber = phoneNumber;
            if (targetNumber == null || targetNumber.isEmpty()) targetNumber = to;
            if (targetNumber == null || targetNumber.isEmpty()) targetNumber = phone;
            
            // Get language parameters
            String sourceLang = (fromLanguage != null && !fromLanguage.isEmpty()) ? fromLanguage : sourceLanguage;
            String targetLang = (toLanguage != null && !toLanguage.isEmpty()) ? toLanguage : targetLanguage;
            
            if (sourceLang == null) sourceLang = "en";
            if (targetLang == null) targetLang = "es";
            
            sourceLang = convertLanguageCode(sourceLang);
            targetLang = convertLanguageCode(targetLang);
            
            System.out.println("Extracted - Phone: " + targetNumber + ", From: " + sourceLang + ", To: " + targetLang);
            
            if (targetNumber == null || targetNumber.trim().isEmpty()) {
                response.put("status", "error");
                response.put("message", "Phone number is required");
                return ResponseEntity.badRequest().body(response);
            }
            
            if (!isValidPhoneNumber(targetNumber)) {
                response.put("status", "error");
                response.put("message", "Invalid phone number format");
                return ResponseEntity.badRequest().body(response);
            }
            
            if (accountSid.isEmpty() || authToken.isEmpty() || twilioPhoneNumber.isEmpty()) {
                response.put("status", "error");
                response.put("message", "Twilio credentials not configured");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
            }
            
            initTwilio();
            
            // Generate unique conference ID
            String conferenceId = "translation-" + System.currentTimeMillis();
            
            // Create TwiML URL for conference with parameters
            String twimlUrl = baseUrl + "/api/voice?conference=" + conferenceId + 
                             "&to=" + targetNumber + "&source=" + sourceLang + "&target=" + targetLang;
            
            System.out.println("Creating conference call to: " + targetNumber + " from: " + twilioPhoneNumber);
            
            Call call = Call.creator(
                new PhoneNumber(targetNumber),
                new PhoneNumber(twilioPhoneNumber),
                URI.create(twimlUrl)
            ).create();
            
            System.out.println("Call created successfully: " + call.getSid() + " Conference: " + conferenceId);
            
            response.put("status", "success");
            response.put("message", "Translation conference call initiated successfully");
            response.put("callSid", call.getSid());
            response.put("conferenceId", conferenceId);
            response.put("to", targetNumber);
            response.put("from", twilioPhoneNumber);
            response.put("sourceLanguage", sourceLang);
            response.put("targetLanguage", targetLang);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            System.out.println("Error in start-call: " + e.getMessage());
            e.printStackTrace();
            response.put("status", "error");
            response.put("message", "Failed to initiate call: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    
    @PostMapping(value = "/voice", produces = MediaType.APPLICATION_XML_VALUE)
    public String handleVoiceWebhook(
            @RequestParam(required = false) String conference,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "en-US") String source,
            @RequestParam(required = false, defaultValue = "es-ES") String target) {
        
        try {
            System.out.println("Voice webhook called - Conference: " + conference + ", To: " + to + ", Source: " + source + ", Target: " + target);
            
            StringBuilder twiml = new StringBuilder();
            twiml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            twiml.append("<Response>");
            
            // Start media streaming for translation
            String streamUrl = baseUrl.replace("https://", "wss://") + "/stream?source=" + source + "&target=" + target;
            twiml.append("<Start>");
            twiml.append("<Stream url=\"").append(streamUrl).append("\" />");
            twiml.append("</Start>");
            
            twiml.append("<Say voice=\"alice\">");
            twiml.append("Welcome to the translation service. Joining conference with translation from ");
            twiml.append(getLanguageName(source)).append(" to ").append(getLanguageName(target)).append(".");
            twiml.append("</Say>");
            
            // Join conference room
            twiml.append("<Dial>");
            twiml.append("<Conference statusCallback=\"").append(baseUrl).append("/api/conference-status\">");
            twiml.append(conference);
            twiml.append("</Conference>");
            twiml.append("</Dial>");
            
            twiml.append("</Response>");
            
            // Invite the target number to the same conference
            if (to != null && !to.isEmpty() && conference != null) {
                inviteToConference(to, conference, target, source); // Reverse languages for second participant
            }
            
            System.out.println("Generated TwiML: " + twiml.toString());
            
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
            
            // Add media streaming for this participant too
            String streamUrl = baseUrl.replace("https://", "wss://") + "/stream?source=" + source + "&target=" + target;
            twiml.append("<Start>");
            twiml.append("<Stream url=\"").append(streamUrl).append("\" />");
            twiml.append("</Start>");
            
            twiml.append("<Say voice=\"alice\">");
            twiml.append("You have been invited to a translation call with ");
            twiml.append(getLanguageName(source)).append(" to ").append(getLanguageName(target));
            twiml.append(" translation. Joining now.");
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
            
            // Small delay to ensure first participant joins first
            Thread.sleep(2000);
            
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
