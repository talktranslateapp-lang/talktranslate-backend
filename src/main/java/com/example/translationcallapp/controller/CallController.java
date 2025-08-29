package com.example.translationcallapp.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.twilio.Twilio;
import com.twilio.jwt.accesstoken.AccessToken;
import com.twilio.jwt.accesstoken.VoiceGrant;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Conference;
import com.twilio.twiml.voice.Dial;
import com.twilio.type.PhoneNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import java.util.*;

@RestController
@RequestMapping("/api/call")
@CrossOrigin(origins = "*")
public class CallController {
    
    private static final Logger logger = LoggerFactory.getLogger(CallController.class);
    
    @Value("${twilio.account.sid}")
    private String accountSid;
    
    @Value("${twilio.auth.token}")
    private String authToken;
    
    @Value("${twilio.api.key}")
    private String apiKey;
    
    @Value("${twilio.api.secret}")
    private String apiSecret;
    
    @Value("${twilio.twiml.app.sid}")
    private String twimlAppSid;
    
    @PostConstruct
    public void init() {
        Twilio.init(accountSid, authToken);
    }
    
    @GetMapping("/token")
    public ResponseEntity<Map<String, String>> getAccessToken(@RequestParam String identity) {
        try {
            // Create access token for Twilio Client SDK
            AccessToken accessToken = new AccessToken.Builder(
                accountSid,
                apiKey,
                apiSecret
            )
            .identity(identity)
            .ttl(3600) // 1 hour
            .build();
            
            // Add Voice grant for outbound calling
            VoiceGrant voiceGrant = new VoiceGrant();
            voiceGrant.setOutgoingApplicationSid(twimlAppSid);
            voiceGrant.setIncomingAllow(true);
            accessToken.addGrant(voiceGrant);
            
            Map<String, String> response = new HashMap<>();
            response.put("token", accessToken.toJwt());
            response.put("identity", identity);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error generating access token", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to generate access token"));
        }
    }
    
    @PostMapping("/voice/incoming")
    public ResponseEntity<String> handleIncomingCall(@RequestParam Map<String, String> params) {
        try {
            String from = params.get("From");
            String to = params.get("To");
            
            logger.info("Incoming call from {} to {}", from, to);
            
            VoiceResponse.Builder builder = new VoiceResponse.Builder();
            
            // Create conference room for translation
            String conferenceName = "translate-" + System.currentTimeMillis();
            
            Conference.Builder conferenceBuilder = new Conference.Builder(conferenceName)
                .startConferenceOnEnter(true)
                .endConferenceOnExit(false)
                .record(Conference.Record.DO_NOT_RECORD)
                .statusCallback("https://talktranslate-backend-production.up.railway.app/api/call/conference/status")
                .statusCallbackEvent(Arrays.asList(
                    Conference.Event.START,
                    Conference.Event.END,
                    Conference.Event.JOIN,
                    Conference.Event.LEAVE
                ));
            
            Dial dial = new Dial.Builder()
                .addConference(conferenceBuilder.build())
                .build();
            
            builder.addDial(dial);
            
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(builder.build().toXml());
                
        } catch (Exception e) {
            logger.error("Error handling incoming call", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_XML)
                .body("<Response><Say>An error occurred</Say></Response>");
        }
    }
    
    @PostMapping("/voice/status")
    public ResponseEntity<String> handleCallStatus(@RequestParam Map<String, String> params) {
        try {
            String callSid = params.get("CallSid");
            String callStatus = params.get("CallStatus");
            String from = params.get("From");
            String to = params.get("To");
            
            logger.info("Call status update - CallSid: {}, Status: {}, From: {}, To: {}", 
                       callSid, callStatus, from, to);
            
            return ResponseEntity.ok("OK");
            
        } catch (Exception e) {
            logger.error("Error handling call status", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error");
        }
    }
    
    @PostMapping("/conference/status")
    public ResponseEntity<String> handleConferenceStatus(@RequestParam Map<String, String> params) {
        try {
            String conferenceSid = params.get("ConferenceSid");
            String friendlyName = params.get("FriendlyName");
            String statusCallbackEvent = params.get("StatusCallbackEvent");
            
            logger.info("Conference status update - ConferenceSid: {}, FriendlyName: {}, Event: {}", 
                       conferenceSid, friendlyName, statusCallbackEvent);
            
            return ResponseEntity.ok("OK");
            
        } catch (Exception e) {
            logger.error("Error handling conference status", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Error");
        }
    }
    
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> healthCheck() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "OK");
        response.put("timestamp", new Date().toString());
        response.put("service", "CallController");
        return ResponseEntity.ok(response);
    }
}
