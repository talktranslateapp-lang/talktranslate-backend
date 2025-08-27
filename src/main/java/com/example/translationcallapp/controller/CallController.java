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
            @RequestParam(required = false, defaultValue = "en") String sourceLanguage,
            @RequestParam(required = false, defaultValue = "es") String targetLanguage) {
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            // Validate phone number format
            if (!isValidPhoneNumber(to)) {
                response.put("status", "error");
                response.put("message", "Invalid phone number format");
                return ResponseEntity.badRequest().body(response);
            }
            
            // Check if Twilio is configured
            if (accountSid.isEmpty() || authToken.isEmpty() || twilioPhoneNumber.isEmpty()) {
                response.put("status", "error");
                response.put("message", "Twilio credentials not configured");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
            }
            
            // Initialize Twilio
            initTwilio();
            
            // Create the call
            String twimlUrl = baseUrl + "/api/voice?to=" + to + "&source=" + sourceLanguage + "&target=" + targetLanguage;
            
            Call call = Call.creator(
                new PhoneNumber(to),
                new PhoneNumber(twilioPhoneNumber),
                URI.create(twimlUrl)
            ).create();
            
            response.put("status", "success");
            response.put("message", "Call initiated successfully");
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
            @RequestParam(required = false, defaultValue = "en") String source,
            @RequestParam(required = false, defaultValue = "es") String target) {
        
        try {
            // Simple TwiML response using string concatenation
            StringBuilder twiml = new StringBuilder();
            twiml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            twiml.append("<Response>");
            twiml.append("<Say voice=\"alice\">");
            twiml.append("Welcome to the translation service. You will be connected with real-time translation from ");
            twiml.append(source).append(" to ").append(target).append(".");
            twiml.append("</Say>");
            
            if (to != null && !to.isEmpty()) {
                twiml.append("<Dial>");
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
            // Fallback response
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Say voice=\"alice\">Sorry, there was an error processing your call. Please try again later.</Say></Response>";
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
    
    private boolean isValidPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return false;
        }
        
        // Remove all non-digit characters except +
        String cleaned = phoneNumber.replaceAll("[^+\\d]", "");
        
        // Must start with + and have at least 10 digits
        if (!cleaned.startsWith("+")) {
            cleaned = "+1" + cleaned; // Default to US country code
        }
        
        // Basic validation: + followed by 10-15 digits
        return cleaned.matches("\\+\\d{10,15}");
    }
}
