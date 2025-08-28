package com.example.translationcallapp.websocket;

import com.example.translationcallapp.model.TwilioMediaMessage;
import com.example.translationcallapp.service.GoogleCloudService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.gax.rpc.BidiStreamingCallable;
import com.google.api.gax.rpc.ClientStream;
import com.google.api.gax.rpc.ResponseObserver;
import com.google.api.gax.rpc.StreamController;
import com.google.cloud.speech.v1.StreamingRecognizeRequest;
import com.google.cloud.speech.v1.StreamingRecognizeResponse;
import com.google.cloud.speech.v1.StreamingRecognitionConfig;
import com.google.cloud.speech.v1.RecognitionConfig;
import com.google.protobuf.ByteString;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ServerEndpoint("/stream")
@Component
public class TwilioMediaStreamEndpoint {
    
    private static GoogleCloudService googleCloudService;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final Map<String, ClientStream<StreamingRecognizeRequest>> speechStreams = new ConcurrentHashMap<>();
    private final Map<String, String> sessionLanguages = new ConcurrentHashMap<>();
    
    @Autowired
    public void setGoogleCloudService(GoogleCloudService service) {
        googleCloudService = service;
    }
    
    @OnOpen
    public void onOpen(Session session, @jakarta.websocket.server.PathParam("source") String source, 
                       @jakarta.websocket.server.PathParam("target") String target) {
        System.out.println("WebSocket opened: " + session.getId());
        
        // Extract language parameters from query string
        String queryString = session.getQueryString();
        if (queryString != null) {
            String[] params = queryString.split("&");
            for (String param : params) {
                String[] keyValue = param.split("=");
                if (keyValue.length == 2) {
                    if ("source".equals(keyValue[0])) {
                        sessionLanguages.put(session.getId() + "_source", keyValue[1]);
                    } else if ("target".equals(keyValue[0])) {
                        sessionLanguages.put(session.getId() + "_target", keyValue[1]);
                    }
                }
            }
        }
        
        // Set default languages if not provided
        sessionLanguages.putIfAbsent(session.getId() + "_source", "en-US");
        sessionLanguages.putIfAbsent(session.getId() + "_target", "es-ES");
        
        System.out.println("Languages for session " + session.getId() + ": " +
                sessionLanguages.get(session.getId() + "_source") + " -> " +
                sessionLanguages.get(session.getId() + "_target"));
                
        initializeSpeechStream(session);
    }
    
    @OnMessage
    public void onMessage(String message, Session session) {
        try {
            TwilioMediaMessage mediaMessage = objectMapper.readValue(message, TwilioMediaMessage.class);
            
            switch (mediaMessage.getEvent()) {
                case "connected":
                    System.out.println("Stream connected: " + session.getId());
                    break;
                    
                case "start":
                    System.out.println("Stream started: " + mediaMessage.getStart().getStreamSid());
                    break;
                    
                case "media":
                    handleMediaMessage(mediaMessage, session);
                    break;
                    
                case "stop":
                    System.out.println("Stream stopped: " + mediaMessage.getStreamSid());
                    cleanup(session);
                    break;
                    
                default:
                    System.out.println("Unknown event: " + mediaMessage.getEvent());
                    break;
            }
        } catch (Exception e) {
            System.err.println("Error processing WebSocket message: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    @OnClose
    public void onClose(Session session) {
        System.out.println("WebSocket closed: " + session.getId());
        cleanup(session);
    }
    
    @OnError
    public void onError(Session session, Throwable throwable) {
        System.err.println("WebSocket error for session " + session.getId() + ": " + throwable.getMessage());
        throwable.printStackTrace();
        cleanup(session);
    }
    
    private void initializeSpeechStream(Session session) {
        try {
            String sourceLanguage = sessionLanguages.get(session.getId() + "_source");
            BidiStreamingCallable<StreamingRecognizeRequest, StreamingRecognizeResponse> callable = 
                    googleCloudService.createSpeechStream(sourceLanguage);
            
            ResponseObserver<StreamingRecognizeResponse> responseObserver = 
                    new ResponseObserver<StreamingRecognizeResponse>() {
                @Override
                public void onStart(StreamController controller) {
                    System.out.println("Speech stream started for session: " + session.getId());
                }
                
                @Override
                public void onResponse(StreamingRecognizeResponse response) {
                    if (response.getResultsCount() > 0) {
                        String transcript = response.getResults(0).getAlternatives(0).getTranscript();
                        boolean isFinal = response.getResults(0).getIsFinal();
                        
                        System.out.println("Transcript (" + (isFinal ? "final" : "interim") + "): " + transcript);
                        
                        if (isFinal && !transcript.trim().isEmpty()) {
                            handleTranscription(transcript, session);
                        }
                    }
                }
                
                @Override
                public void onError(Throwable t) {
                    System.err.println("Speech recognition error for session " + session.getId() + ": " + t.getMessage());
                }
                
                @Override
                public void onComplete() {
                    System.out.println("Speech recognition completed for session: " + session.getId());
                }
            };
            
            ClientStream<StreamingRecognizeRequest> clientStream = callable.splitCall(responseObserver);
            speechStreams.put(session.getId(), clientStream);
            
            // Send initial configuration
            RecognitionConfig recognitionConfig = RecognitionConfig.newBuilder()
                    .setEncoding(RecognitionConfig.AudioEncoding.MULAW)
                    .setSampleRateHertz(8000)
                    .setLanguageCode(sourceLanguage)
                    .setEnableAutomaticPunctuation(true)
                    .build();
                    
            StreamingRecognitionConfig streamingConfig = StreamingRecognitionConfig.newBuilder()
                    .setConfig(recognitionConfig)
                    .setInterimResults(true)
                    .setSingleUtterance(false)
                    .build();
            
            StreamingRecognizeRequest configRequest = StreamingRecognizeRequest.newBuilder()
                    .setStreamingConfig(streamingConfig)
                    .build();
            
            clientStream.send(configRequest);
            
        } catch (Exception e) {
            System.err.println("Failed to initialize speech stream for session " + session.getId() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void handleMediaMessage(TwilioMediaMessage mediaMessage, Session session) {
        try {
            String audioPayload = mediaMessage.getMedia().getPayload();
            if (audioPayload != null && !audioPayload.isEmpty()) {
                byte[] audioData = Base64.getDecoder().decode(audioPayload);
                
                // Send audio data to Google Speech-to-Text
                ClientStream<StreamingRecognizeRequest> stream = speechStreams.get(session.getId());
                if (stream != null) {
                    StreamingRecognizeRequest request = StreamingRecognizeRequest.newBuilder()
                            .setAudioContent(ByteString.copyFrom(audioData))
                            .build();
                    
                    stream.send(request);
                }
            }
            
        } catch (Exception e) {
            System.err.println("Error handling media message for session " + session.getId() + ": " + e.getMessage());
        }
    }
    
    private void handleTranscription(String transcript, Session session) {
        try {
            String sourceLanguage = sessionLanguages.get(session.getId() + "_source");
            String targetLanguage = sessionLanguages.get(session.getId() + "_target");
            
            System.out.println("Processing transcription: '" + transcript + "' from " + sourceLanguage + " to " + targetLanguage);
            
            // Translate the text
            googleCloudService.translateText(transcript, getLanguageCodeForTranslation(sourceLanguage), getLanguageCodeForTranslation(targetLanguage))
                    .thenCompose(translatedText -> {
                        System.out.println("Translation result: " + translatedText);
                        
                        // Convert translated text to speech
                        String voiceName = googleCloudService.getDefaultVoiceName(targetLanguage);
                        return googleCloudService.synthesizeSpeech(translatedText, targetLanguage, voiceName);
                    })
                    .thenAccept(audioData -> {
                        // Send translated audio back to Twilio
                        sendAudioToTwilio(audioData, session);
                    })
                    .exceptionally(throwable -> {
                        System.err.println("Translation/TTS error for session " + session.getId() + ": " + throwable.getMessage());
                        return null;
                    });
                    
        } catch (Exception e) {
            System.err.println("Error handling transcription for session " + session.getId() + ": " + e.getMessage());
        }
    }
    
    private void sendAudioToTwilio(byte[] audioData, Session session) {
        try {
            // Convert audio to base64 and send back to Twilio
            String base64Audio = Base64.getEncoder().encodeToString(audioData);
            
            // Create media message to send back
            String mediaMessage = String.format(
                    "{\"event\": \"media\", \"streamSid\": \"%s\", \"media\": {\"payload\": \"%s\"}}",
                    session.getId(), base64Audio
            );
            
            if (session.isOpen()) {
                session.getBasicRemote().sendText(mediaMessage);
                System.out.println("Sent translated audio back to Twilio for session: " + session.getId());
            }
            
        } catch (IOException e) {
            System.err.println("Error sending audio to Twilio for session " + session.getId() + ": " + e.getMessage());
        }
    }
    
    private String getLanguageCodeForTranslation(String speechLanguageCode) {
        // Convert speech language codes to translation language codes
        if (speechLanguageCode.startsWith("en")) return "en";
        if (speechLanguageCode.startsWith("es")) return "es";
        if (speechLanguageCode.startsWith("fr")) return "fr";
        if (speechLanguageCode.startsWith("de")) return "de";
        if (speechLanguageCode.startsWith("it")) return "it";
        if (speechLanguageCode.startsWith("pt")) return "pt";
        if (speechLanguageCode.startsWith("ja")) return "ja";
        if (speechLanguageCode.startsWith("ko")) return "ko";
        if (speechLanguageCode.startsWith("zh")) return "zh";
        if (speechLanguageCode.startsWith("ar")) return "ar";
        if (speechLanguageCode.startsWith("ru")) return "ru";
        if (speechLanguageCode.startsWith("hi")) return "hi";
        return "en"; // default
    }
    
    private void cleanup(Session session) {
        String sessionId = session.getId();
        
        // Close speech stream
        ClientStream<StreamingRecognizeRequest> stream = speechStreams.remove(sessionId);
        if (stream != null) {
            try {
                stream.closeSend();
            } catch (Exception e) {
                System.err.println("Error closing speech stream for session " + sessionId + ": " + e.getMessage());
            }
        }
        
        // Remove language mappings
        sessionLanguages.remove(sessionId + "_source");
        sessionLanguages.remove(sessionId + "_target");
        
        System.out.println("Cleanup completed for session: " + sessionId);
    }
}
