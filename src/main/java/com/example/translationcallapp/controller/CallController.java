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
            @RequestParam String to,
            @RequestParam(required = false, defaultValue = "en-US") String sourceLanguage,
            @RequestParam(required = false, defaultValue = "es-ES") String targetLanguage) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            if (to == null || to.trim().isEmpty()) {
                response.put("status", "error");
                response.put("message", "Phone number is required");
                return ResponseEntity.badRequest().body(response);
            }
            
            if (!isValidPhoneNumber(to)) {
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
            
            // Create TwiML URL with parameters for translation
            String twimlUrl = baseUrl + "/api/voice?to=" + to + 
                             "&source=" + sourceLanguage + "&target=" + targetLanguage;
            
            Call call = Call.creator(
                new PhoneNumber(to),
                new PhoneNumber(twilioPhoneNumber),
                URI.create(twimlUrl)
            ).create();
            
            response.put("status", "success");
            response.put("message", "Translation call initiated successfully");
            response.put("callSid", call.getSid());
            response.put("to", to);
            response.put("from", twilioPhoneNumber);
            response.put("sourceLanguage", sourceLanguage);
            response.put("targetLanguage", targetLanguage);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Failed to initiate call: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
    
    @PostMapping(value = "/voice", produces = MediaType.APPLICATION_XML_VALUE)
    public String handleVoiceWebhook(
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "en-US") String source,
            @RequestParam(required = false, defaultValue = "es-ES") String target) {
        
        try {
            String streamUrl = baseUrl.replace("https://", "wss://") + "/stream?source=" + source + "&target=" + target;
            
            StringBuilder twiml = new StringBuilder();
            twiml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            twiml.append("<Response>");
            
            // Start media streaming for real-time translation
            twiml.append("<Start>");
            twiml.append("<Stream url=\"").append(streamUrl).append("\" />");
            twiml.append("</Start>");
            
            // Welcome message
            twiml.append("<Say voice=\"alice\">");
            twiml.append("Welcome to the real-time translation service. ");
            twiml.append("You will be connected with translation from ");
            twiml.append(getLanguageName(source)).append(" to ").append(getLanguageName(target));
            twiml.append(".</Say>");
            
            // Connect to the target number
            if (to != null && !to.isEmpty()) {
                twiml.append("<Dial timeout=\"30\" callerId=\"").append(twilioPhoneNumber).append("\">");
                twiml.append("<Number>").append(to).append("</Number>");
                twiml.append("</Dial>");
            } else {
                twiml.append("<Say voice=\"alice\">");
                twiml.append("No target number provided. Please try again.");
                twiml.append("</Say>");
            }
            
            twiml.append("</Response>");
            
            return twiml.toString();
            
        } catch (Exception e) {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Say voice=\"alice\">Sorry, there was an error setting up translation. Please try again.</Say></Response>";
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
