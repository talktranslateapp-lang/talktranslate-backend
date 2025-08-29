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
        try {
            RecognitionConfig config = RecognitionConfig.newBuilder()
                .setEncoding(RecognitionConfig.AudioEncoding.MULAW)
                .setSampleRateHertz(8000)
                .setLanguageCode(languageCode)
                .build();
            
            RecognitionAudio audio = RecognitionAudio.newBuilder()
                .setContent(ByteString.copyFrom(audioData))
                .build();
            
            RecognizeResponse response = speechClient.recognize(config, audio);
            
            return response.getResultsList().stream()
                .map(result -> result.getAlternativesList().get(0).getTranscript())
                .collect(Collectors.joining(" "));
                
        } catch (Exception e) {
            log.error("Speech-to-text conversion failed", e);
            return "";
        }
    }
    
    public String translateText(String text, String targetLanguage, String sourceLanguage) {
        try {
            Translation translation = translate.translate(
                text,
                Translate.TranslateOption.sourceLanguage(sourceLanguage),
                Translate.TranslateOption.targetLanguage(targetLanguage)
            );
            
            return translation.getTranslatedText();
            
        } catch (Exception e) {
            log.error("Text translation failed", e);
            return text; // Return original text if translation fails
        }
    }
    
    public byte[] textToSpeech(String text, String languageCode) {
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
            
            return response.getAudioContent().toByteArray();
            
        } catch (Exception e) {
            log.error("Text-to-speech conversion failed", e);
            return new byte[0];
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
        } catch (Exception e) {
            log.error("Error closing Google Cloud clients", e);
        }
    }
}
