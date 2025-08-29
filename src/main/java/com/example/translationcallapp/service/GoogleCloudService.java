package com.example.translationcallapp.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.speech.v1.*;
import com.google.cloud.texttospeech.v1.*;
import com.google.cloud.translate.Translate;
import com.google.cloud.translate.TranslateOptions;
import com.google.cloud.translate.Translation;
import com.google.protobuf.ByteString;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.stream.Collectors;

@Service
@Slf4j
public class GoogleCloudService {
    
    @Value("${google.cloud.project-id}")
    private String projectId;
    
    @Value("${google.application.credentials.json}")
    private String credentialsJson;
    
    private SpeechClient speechClient;
    private TextToSpeechClient textToSpeechClient;
    private Translate translate;
    
    @PostConstruct
    public void init() {
        try {
            // Create credentials from JSON string
            GoogleCredentials credentials = GoogleCredentials.fromStream(
                new ByteArrayInputStream(credentialsJson.getBytes())
            ).createScoped(Arrays.asList(
                "https://www.googleapis.com/auth/cloud-platform"
            ));
            
            // Initialize Speech-to-Text client
            SpeechSettings speechSettings = SpeechSettings.newBuilder()
                .setCredentialsProvider(() -> credentials)
                .build();
            this.speechClient = SpeechClient.create(speechSettings);
            
            // Initialize Text-to-Speech client
            TextToSpeechSettings ttsSettings = TextToSpeechSettings.newBuilder()
                .setCredentialsProvider(() -> credentials)
                .build();
            this.textToSpeechClient = TextToSpeechClient.create(ttsSettings);
            
            // Initialize Translation client
            TranslateOptions translateOptions = TranslateOptions.newBuilder()
                .setCredentials(credentials)
                .setProjectId(projectId)
                .build();
            this.translate = translateOptions.getService();
            
            log.info("Google Cloud services initialized successfully for project: {}", projectId);
            
        } catch (Exception e) {
            log.error("Failed to initialize Google Cloud services", e);
            throw new RuntimeException("Google Cloud initialization failed", e);
        }
    }
    
    public String speechToText(byte[] audioData, String languageCode) {
        if (speechClient == null) {
            log.error("Speech client is not initialized");
            return "";
        }
        
        try {
            RecognitionConfig config = RecognitionConfig.newBuilder()
                .setEncoding(RecognitionConfig.AudioEncoding.MULAW)
                .setSampleRateHertz(8000)
                .setLanguageCode(languageCode)
                .setEnableAutomaticPunctuation(true)
                .build();
            
            RecognitionAudio audio = RecognitionAudio.newBuilder()
                .setContent(ByteString.copyFrom(audioData))
                .build();
            
            RecognizeResponse response = speechClient.recognize(config, audio);
            
            String transcript = response.getResultsList().stream()
                .map(result -> result.getAlternativesList().get(0).getTranscript())
                .collect(Collectors.joining(" "));
                
            log.debug("Speech-to-text result: {}", transcript);
            return transcript;
                
        } catch (Exception e) {
            log.error("Speech-to-text conversion failed", e);
            return "";
        }
    }
    
    public String translateText(String text, String targetLanguage, String sourceLanguage) {
        if (translate == null) {
            log.error("Translate client is not initialized");
            return text;
        }
        
        if (text == null || text.trim().isEmpty()) {
            return text;
        }
        
        try {
            Translation translation = translate.translate(
                text,
                Translate.TranslateOption.sourceLanguage(sourceLanguage),
                Translate.TranslateOption.targetLanguage(targetLanguage)
            );
            
            String translatedText = translation.getTranslatedText();
            log.debug("Translation result: {} -> {}", text, translatedText);
            return translatedText;
            
        } catch (Exception e) {
            log.error("Text translation failed for text: '{}' from {} to {}", text, sourceLanguage, targetLanguage, e);
            return text; // Return original text if translation fails
        }
    }
    
    public byte[] textToSpeech(String text, String languageCode) {
        if (textToSpeechClient == null) {
            log.error("Text-to-speech client is not initialized");
            return new byte[0];
        }
        
        if (text == null || text.trim().isEmpty()) {
            return new byte[0];
        }
        
        try {
            SynthesisInput input = SynthesisInput.newBuilder().setText(text).build();
            
            VoiceSelectionParams voice = VoiceSelectionParams.newBuilder()
                .setLanguageCode(languageCode)
                .setSsmlGender(SsmlVoiceGender.NEUTRAL)
                .build();
            
            AudioConfig audioConfig = AudioConfig.newBuilder()
                .setAudioEncoding(AudioEncoding.MULAW)
                .setSampleRateHertz(8000)
                .build();
            
            SynthesizeSpeechResponse response = textToSpeechClient.synthesizeSpeech(
                input, voice, audioConfig
            );
            
            byte[] audioContent = response.getAudioContent().toByteArray();
            log.debug("Text-to-speech generated {} bytes for text: {}", audioContent.length, text);
            return audioContent;
            
        } catch (Exception e) {
            log.error("Text-to-speech conversion failed for text: '{}' in language: {}", text, languageCode, e);
            return new byte[0];
        }
    }
    
    public boolean isInitialized() {
        return speechClient != null && textToSpeechClient != null && translate != null;
    }
    
    public String getProjectId() {
        return projectId;
    }
    
    // Language code utilities
    public String normalizeLanguageCode(String languageCode) {
        if (languageCode == null) {
            return "en-US";
        }
        
        // Convert common language codes to Google Cloud format
        switch (languageCode.toLowerCase()) {
            case "en":
            case "english":
                return "en-US";
            case "es":
            case "spanish":
                return "es-ES";
            case "fr":
            case "french":
                return "fr-FR";
            case "de":
            case "german":
                return "de-DE";
            case "it":
            case "italian":
                return "it-IT";
            case "ja":
            case "japanese":
                return "ja-JP";
            case "ko":
            case "korean":
                return "ko-KR";
            case "zh":
            case "chinese":
                return "zh-CN";
            default:
                return languageCode;
        }
    }
    
    // Extract language code for translation (remove region)
    public String getTranslateLanguageCode(String fullLanguageCode) {
        if (fullLanguageCode == null) {
            return "en";
        }
        
        String[] parts = fullLanguageCode.split("-");
        return parts[0].toLowerCase();
    }
    
    // Get default voice name for language
    public String getDefaultVoiceName(String languageCode) {
        if (languageCode == null) {
            return "en-US-Standard-A";
        }
        
        // Map common language codes to default voices
        switch (languageCode.toLowerCase()) {
            case "en-us":
                return "en-US-Standard-A";
            case "es-es":
                return "es-ES-Standard-A";
            case "fr-fr":
                return "fr-FR-Standard-A";
            case "de-de":
                return "de-DE-Standard-A";
            case "it-it":
                return "it-IT-Standard-A";
            case "ja-jp":
                return "ja-JP-Standard-A";
            case "ko-kr":
                return "ko-KR-Standard-A";
            case "zh-cn":
                return "cmn-CN-Standard-A";
            default:
                return "en-US-Standard-A";
        }
    }
    
    // Create speech stream for real-time processing (placeholder for streaming implementation)
    public Object createSpeechStream(String languageCode) {
        // This would be used for streaming speech recognition
        // For now, return null as streaming is not implemented in this basic version
        log.warn("createSpeechStream called but streaming not implemented. Using batch processing instead.");
        return null;
    }
    
    @PreDestroy
    public void cleanup() {
        try {
            if (speechClient != null) {
                speechClient.close();
                log.info("Speech client closed");
            }
            if (textToSpeechClient != null) {
                textToSpeechClient.close();
                log.info("Text-to-speech client closed");
            }
            log.info("Google Cloud services cleanup completed");
        } catch (Exception e) {
            log.error("Error closing Google Cloud clients", e);
        }
    }
}
