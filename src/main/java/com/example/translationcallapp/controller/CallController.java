@RestController
@RequestMapping("/api/call")
@CrossOrigin(origins = "*")
public class CallController {
    
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
            
            VoiceResponse.Builder builder = new VoiceResponse.Builder();
            
            // Create conference room for translation
            String conferenceName = "translate-" + System.currentTimeMillis();
            
            Conference.Builder conferenceBuilder = new Conference.Builder(conferenceName)
                .startConferenceOnEnter(true)
                .endConferenceOnExit(false)
                .record(Record.DO_NOT_RECORD)
                .statusCallback("https://talktranslate-backend-production.up.railway.app/conference/status")
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
                .body("<Response><Say>An error occurred</Say></Response>");
        }
    }
}
