package com.example.translationcallapp.controller;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.type.PhoneNumber;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Say;
import com.twilio.twiml.voice.Dial;
import com.twilio.twiml.voice.Number;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;

import javax.annotation.PostConstruct;
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
    
    @PostConstruct
    public void initTwilio() {
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
            VoiceResponse.Builder responseBuilder = VoiceResponse.builder();
            
            // Welcome message
            Say welcomeMessage = Say.builder()
                .voice(Say.Voice.ALICE)
                .language(Say.Language.EN)
                .speech("Welcome to the translation service. You will be connected to " + to + " with real-time translation from " + source + " to " + target + ".")
                .build();
            
            responseBuilder.say(welcomeMessage);
            
            // Connect to the target number if provided
            if (to != null && !to.isEmpty()) {
                Number number = Number.builder(to).build();
                Dial dial = Dial.builder()
                        .number(number)
                        .build();
                responseBuilder.dial(dial);
            } else {
                // If no target number, just play a message
                Say errorMessage = Say.builder()
                    .voice(Say.Voice.ALICE)
                    .speech("No target number provided. Please try again.")
                    .build();
                responseBuilder.say(errorMessage);
            }
            
            VoiceResponse response = responseBuilder.build();
            return response.toXml();
            
        } catch (Exception e) {
            // Fallback response in case of error
            VoiceResponse errorResponse = VoiceResponse.builder()
                .say(Say.builder()
                    .voice(Say.Voice.ALICE)
                    .speech("Sorry, there was an error processing your call. Please try again later.")
                    .build())
                .build();
            return errorResponse.toXml();
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
