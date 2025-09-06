package com.example.translationcallapp.controller;

import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Conference;
import com.twilio.twiml.voice.Dial;
import com.twilio.twiml.voice.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@RestController
@RequestMapping("/webhook")
public class BotWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(BotWebhookController.class);

    @Value("${app.websocket.stream-url}")
    private String websocketStreamUrl;

    /**
     * Webhook endpoint for bot participants
     * Returns TwiML to join the bot to a conference and optionally start media streaming
     */
    @RequestMapping(value = "/bot", method = {RequestMethod.GET, RequestMethod.POST}, 
                   produces = MediaType.APPLICATION_XML_VALUE)
    public void botWebhook(
            @RequestParam String conferenceSid,
            @RequestParam(required = false) String targetLanguage,
            @RequestParam(required = false) String sourceLanguage,
            HttpServletResponse response) throws IOException {

        logger.info("Bot webhook called for conference: {}, source: {}, target: {}", 
                   conferenceSid, sourceLanguage, targetLanguage);

        try {
            // Build the conference TwiML
            Conference.Builder conferenceBuilder = new Conference.Builder(conferenceSid)
                    .muted(false)                    // Bot should not be muted initially
                    .startConferenceOnEnter(true)    // Bot can start the conference
                    .endConferenceOnExit(false)      // Conference continues when bot leaves
                    .statusCallback(buildStatusCallbackUrl(conferenceSid))
                    .statusCallbackEvent("start join leave end");

            Conference conference = conferenceBuilder.build();
            
            // Create dial instruction
            Dial.Builder dialBuilder = new Dial.Builder()
                    .conference(conference);

            // If we have language parameters, we might want to start media streaming
            VoiceResponse.Builder responseBuilder = new VoiceResponse.Builder();

            // Add media streaming if we have language translation requirements
            if (targetLanguage != null && sourceLanguage != null) {
                String streamUrl = websocketStreamUrl + "?conferenceSid=" + conferenceSid 
                                 + "&targetLanguage=" + targetLanguage 
                                 + "&sourceLanguage=" + sourceLanguage;
                
                Stream stream = new Stream.Builder()
                        .url(streamUrl)
                        .build();
                
                responseBuilder.stream(stream);
                logger.info("Added media stream for translation: {}", streamUrl);
            }

            // Add the dial instruction
            Dial dial = dialBuilder.build();
            responseBuilder.dial(dial);

            VoiceResponse twiml = responseBuilder.build();

            // Set response headers
            response.setContentType("application/xml");
            response.setCharacterEncoding("UTF-8");
            
            // Write TwiML response
            String twimlXml = twiml.toXml();
            logger.info("Returning TwiML for bot: {}", twimlXml);
            response.getWriter().print(twimlXml);

        } catch (Exception e) {
            logger.error("Error generating bot webhook TwiML: {}", e.getMessage(), e);
            
            // Return a simple fallback TwiML
            Conference conference = new Conference.Builder(conferenceSid).build();
            Dial dial = new Dial.Builder().conference(conference).build();
            VoiceResponse fallback = new VoiceResponse.Builder().dial(dial).build();
            
            response.setContentType("application/xml");
            response.getWriter().print(fallback.toXml());
        }
    }

    /**
     * Conference status webhook - handles conference events
     */
    @RequestMapping(value = "/conference-status", method = {RequestMethod.GET, RequestMethod.POST})
    public void conferenceStatus(
            @RequestParam String ConferenceSid,
            @RequestParam String StatusCallbackEvent,
            @RequestParam(required = false) String Timestamp,
            HttpServletResponse response) throws IOException {

        logger.info("Conference {} status: {} at {}", ConferenceSid, StatusCallbackEvent, Timestamp);

        // Handle different conference events
        switch (StatusCallbackEvent.toLowerCase()) {
            case "conference-start":
                logger.info("Conference {} started", ConferenceSid);
                break;
            case "conference-end":
                logger.info("Conference {} ended", ConferenceSid);
                break;
            case "participant-join":
                logger.info("Participant joined conference {}", ConferenceSid);
                break;
            case "participant-leave":
                logger.info("Participant left conference {}", ConferenceSid);
                break;
            default:
                logger.info("Unknown conference event: {}", StatusCallbackEvent);
        }

        response.setStatus(HttpServletResponse.SC_OK);
    }

    /**
     * Simple webhook for regular call participants (non-bot)
     */
    @RequestMapping(value = "/participant", method = {RequestMethod.GET, RequestMethod.POST}, 
                   produces = MediaType.APPLICATION_XML_VALUE)
    public void participantWebhook(
            @RequestParam String conferenceSid,
            @RequestParam(required = false) String participantRole,
            HttpServletResponse response) throws IOException {

        logger.info("Participant webhook called for conference: {}, role: {}", conferenceSid, participantRole);

        try {
            // Configure participant settings
            Conference.Builder conferenceBuilder = new Conference.Builder(conferenceSid)
                    .muted(false)
                    .startConferenceOnEnter(true)
                    .endConferenceOnExit(true)  // End conference when regular participant leaves
                    .statusCallback(buildStatusCallbackUrl(conferenceSid))
                    .statusCallbackEvent("join leave");

            Conference conference = conferenceBuilder.build();
            Dial dial = new Dial.Builder().conference(conference).build();
            VoiceResponse twiml = new VoiceResponse.Builder().dial(dial).build();

            response.setContentType("application/xml");
            response.getWriter().print(twiml.toXml());

        } catch (Exception e) {
            logger.error("Error generating participant webhook TwiML: {}", e.getMessage(), e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Health check endpoint for webhook
     */
    @GetMapping("/health")
    public String webhookHealth() {
        return "Webhook endpoints are healthy";
    }

    /**
     * Helper method to build secure streaming URLs
     */
    private String buildSecureStreamUrl(String conferenceSid, String targetLanguage, String sourceLanguage) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String token = botParticipantService.validateWebhookToken(conferenceSid, timestamp, "dummy") ? "secure" : "fallback";
        
        return websocketStreamUrl + "?conferenceSid=" + conferenceSid 
               + "&targetLanguage=" + targetLanguage 
               + "&sourceLanguage=" + sourceLanguage
               + "&timestamp=" + timestamp
               + "&token=" + token;
    }

    /**
     * Helper method to build status callback URLs
     */
    private String buildStatusCallbackUrl(String conferenceSid) {
        String baseWebhookUrl = websocketStreamUrl.replace("/media-stream", "");
        return baseWebhookUrl + "/webhook/conference-status?conferenceSid=" + conferenceSid;
    }
}