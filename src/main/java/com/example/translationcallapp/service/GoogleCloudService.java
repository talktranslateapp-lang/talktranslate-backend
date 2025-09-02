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
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class GoogleCloudService {

    @Value("${GOOGLE_CLOUD_PROJECT_ID}")
    private String projectId;

    @Value("${GOOGLE_APPLICATION_CREDENTIALS_JSON}")
    private String credentialsJson;

    private SpeechClient speechClient;
    private TextToSpeechClient textToSpeechClient;
    private Translate translateService;
    private boolean initialized = false;

    @PostConstruct
    public void init() {
        try {
            log.info("Initializing Google Cloud services...");
            
            // Create credentials from JSON string
            GoogleCredentials credentials = GoogleCredentials
                .fromStream(new ByteArrayInputStream(credentialsJson.getBytes()))
                .createScoped(Arrays.asList(
                    "https://www.googleapis.com/auth/cloud-platform",
                    "https://www.googleapis.com/auth/speech",
                    "https://www.googleapis.com/auth/texttospeech",
                    "https://www.googleapis.com/auth/cloud-translation"
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

            // Initialize Translation service
            this.translateService = TranslateOptions.newBuilder()
                .setCredentials(credentials)
                .setProjectId(projectId)
                .build()
                .getService();

            this.initialized = true;
            log.info("Google Cloud services initialized successfully");

        } catch (Exception e) {
            log.error("Failed to initialize Google Cloud services", e);
            this.initialized = false;
        }
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (speechClient != null) {
                speechClient.close();
            }
            if (textToSpeechClient != null) {
                textToSpeechClient.close();
            }
            log.info("Google Cloud services cleaned up");
        } catch (Exception e) {
            log.error("Error during cleanup", e);
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public CompletableFuture<String> transcribeAudio(byte[] audioData, String languageCode) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!initialized || speechClient == null) {
                    log.error("Speech client not initialized");
                    return "";
                }

                // Normalize language code for Google Cloud
                String normalizedLanguageCode = normalizeLanguageCode(languageCode);

                RecognitionConfig config = RecognitionConfig.newBuilder()
                    .setEncoding(RecognitionConfig.AudioEncoding.MULAW)
                    .setSampleRateHertz(8000)
                    .setLanguageCode(normalizedLanguageCode)
                    .setEnableAutomaticPunctuation(true)
                    .build();

                RecognitionAudio audio = RecognitionAudio.newBuilder()
                    .setContent(ByteString.copyFrom(audioData))
                    .build();

                RecognizeResponse response = speechClient.recognize(config, audio);
                List<SpeechRecognitionResult> results = response.getResultsList();

                if (!results.isEmpty()) {
                    String transcript = results.get(0).getAlternatives(0).getTranscript();
                    log.debug("Transcribed: {}", transcript);
                    return transcript;
                }

                return "";

            } catch (Exception e) {
                log.error("Error transcribing audio", e);
                return "";
            }
        });
    }

    public CompletableFuture<String> translateText(String text, String sourceLanguage, String targetLanguage) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!initialized || translateService == null) {
                    log.error("Translation service not initialized");
                    return text; // Return original text as fallback
                }

                if (text == null || text.trim().isEmpty()) {
                    return text;
                }

                // Normalize language codes
                String normalizedSource = normalizeLanguageCodeForTranslation(sourceLanguage);
                String normalizedTarget = normalizeLanguageCodeForTranslation(targetLanguage);

                // Skip translation if source and target are the same
                if (normalizedSource.equals(normalizedTarget)) {
                    return text;
                }

                Translation translation = translateService.translate(
                    text,
                    Translate.TranslateOption.sourceLanguage(normalizedSource),
                    Translate.TranslateOption.targetLanguage(normalizedTarget)
                );

                String translatedText = translation.getTranslatedText();
                log.debug("Translated '{}' from {} to {}: '{}'", text, normalizedSource, normalizedTarget, translatedText);
                return translatedText;

            } catch (Exception e) {
                log.error("Error translating text: " + text, e);
                return text; // Return original text as fallback
            }
        });
    }

    public CompletableFuture<byte[]> synthesizeSpeech(String text, String languageCode) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!initialized || textToSpeechClient == null) {
                    log.error("Text-to-Speech client not initialized");
                    return new byte[0];
                }

                if (text == null || text.trim().isEmpty()) {
                    return new byte[0];
                }

                // Normalize language code
                String normalizedLanguageCode = normalizeLanguageCode(languageCode);

                SynthesisInput input = SynthesisInput.newBuilder()
                    .setText(text)
                    .build();

                VoiceSelectionParams voice = VoiceSelectionParams.newBuilder()
                    .setLanguageCode(normalizedLanguageCode)
                    .setSsmlGender(SsmlVoiceGender.NEUTRAL)
                    .build();

                AudioConfig audioConfig = AudioConfig.newBuilder()
                    .setAudioEncoding(AudioEncoding.MULAW)
                    .setSampleRateHertz(8000)
                    .build();

                SynthesizeSpeechResponse response = textToSpeechClient.synthesizeSpeech(input, voice, audioConfig);
                byte[] audioData = response.getAudioContent().toByteArray();

                log.debug("Synthesized speech for text: '{}' in language: {}", text, normalizedLanguageCode);
                return audioData;

            } catch (Exception e) {
                log.error("Error synthesizing speech for text: " + text, e);
                return new byte[0];
            }
        });
    }

    // Process audio chunk for real-time translation
    public void processAudioChunk(byte[] audioData, String sourceLanguage, String targetLanguage, 
                                  AudioChunkCallback callback) {
        if (!initialized) {
            log.warn("Google Cloud services not initialized, skipping audio processing");
            return;
        }

        transcribeAudio(audioData, sourceLanguage)
            .thenCompose(transcript -> {
                if (transcript.isEmpty()) {
                    return CompletableFuture.completedFuture("");
                }
                return translateText(transcript, sourceLanguage, targetLanguage);
            })
            .thenCompose(translatedText -> {
                if (translatedText.isEmpty()) {
                    return CompletableFuture.completedFuture(new byte[0]);
                }
                return synthesizeSpeech(translatedText, targetLanguage);
            })
            .thenAccept(audioOutput -> {
                if (audioOutput.length > 0) {
                    callback.onAudioProcessed(audioOutput);
                }
            })
            .exceptionally(throwable -> {
                log.error("Error processing audio chunk", throwable);
                return null;
            });
    }

    // Normalize language codes for Google Cloud Speech/TTS
    private String normalizeLanguageCode(String languageCode) {
        if (languageCode == null) return "en-US";
        
        switch (languageCode.toLowerCase()) {
            case "en": case "english": return "en-US";
            case "es": case "spanish": return "es-ES";
            case "fr": case "french": return "fr-FR";
            case "de": case "german": return "de-DE";
            case "it": case "italian": return "it-IT";
            case "ja": case "japanese": return "ja-JP";
            case "ko": case "korean": return "ko-KR";
            case "zh": case "chinese": case "mandarin": return "zh-CN";
            default: return languageCode;
        }
    }

    // Normalize language codes for Google Cloud Translation
    private String normalizeLanguageCodeForTranslation(String languageCode) {
        if (languageCode == null) return "en";
        
        switch (languageCode.toLowerCase()) {
            case "en-us": case "english": return "en";
            case "es-es": case "spanish": return "es";
            case "fr-fr": case "french": return "fr";
            case "de-de": case "german": return "de";
            case "it-it": case "italian": return "it";
            case "ja-jp": case "japanese": return "ja";
            case "ko-kr": case "korean": return "ko";
            case "zh-cn": case "chinese": case "mandarin": return "zh";
            default: 
                // Extract language part if it's in format "xx-XX"
                if (languageCode.contains("-")) {
                    return languageCode.split("-")[0].toLowerCase();
                }
                return languageCode.toLowerCase();
        }
    }

    public interface AudioChunkCallback {
        void onAudioProcessed(byte[] audioData);
    }
}