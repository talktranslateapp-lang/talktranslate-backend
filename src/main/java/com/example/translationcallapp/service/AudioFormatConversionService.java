package com.example.translationcallapp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

@Service
@Slf4j
public class AudioFormatConversionService {

    @Value("${audio.format.conversion.buffer-size:8192}")
    private int bufferSize;

    @Value("${audio.format.conversion.timeout:30}")
    private int conversionTimeoutSeconds;

    @Value("${audio.format.sample-rate:8000}")
    private int sampleRate;

    @Value("${audio.format.bit-depth:16}")
    private int bitDepth;

    // μ-law compression table for 8-bit μ-law encoding
    private static final short[] ULAW_TO_LINEAR = new short[256];
    private static final byte[] LINEAR_TO_ULAW = new byte[65536];

    static {
        // Initialize μ-law to linear conversion table
        for (int i = 0; i < 256; i++) {
            ULAW_TO_LINEAR[i] = ulawToLinear((byte) i);
        }
        
        // Initialize linear to μ-law conversion table
        for (int i = 0; i < 65536; i++) {
            LINEAR_TO_ULAW[i] = linearToUlaw((short) (i - 32768));
        }
    }

    /**
     * Convert Twilio μ-law audio to OpenAI PCM format (24kHz, 16-bit)
     * Used for the Realtime API (when available)
     */
    public String convertTwilioToOpenAI(String base64UlawAudio) {
        try {
            if (base64UlawAudio == null || base64UlawAudio.isEmpty()) {
                log.warn("Empty audio data provided for conversion");
                return null;
            }

            // Decode base64 μ-law audio
            byte[] ulawData = Base64.getDecoder().decode(base64UlawAudio);
            
            // Convert μ-law to 16-bit PCM
            short[] pcmData = convertUlawToPcm(ulawData);
            
            // Resample from 8kHz to 24kHz for OpenAI
            short[] resampledData = resample(pcmData, 8000, 24000);
            
            // Convert to byte array (little-endian)
            byte[] pcmBytes = convertPcmToBytes(resampledData);
            
            return Base64.getEncoder().encodeToString(pcmBytes);

        } catch (Exception e) {
            log.error("Failed to convert Twilio audio to OpenAI format", e);
            return null;
        }
    }

    /**
     * Convert OpenAI PCM audio back to Twilio μ-law format
     * Used for the Realtime API (when available)
     */
    public String convertOpenAIToTwilio(String base64PcmAudio) {
        try {
            if (base64PcmAudio == null || base64PcmAudio.isEmpty()) {
                log.warn("Empty audio data provided for conversion");
                return null;
            }

            // Decode base64 PCM audio
            byte[] pcmBytes = Base64.getDecoder().decode(base64PcmAudio);
            
            // Convert bytes to PCM samples
            short[] pcmData = convertBytesToPcm(pcmBytes);
            
            // Resample from 24kHz to 8kHz for Twilio
            short[] resampledData = resample(pcmData, 24000, 8000);
            
            // Convert PCM to μ-law
            byte[] ulawData = convertPcmToUlaw(resampledData);
            
            return Base64.getEncoder().encodeToString(ulawData);

        } catch (Exception e) {
            log.error("Failed to convert OpenAI audio to Twilio format", e);
            return null;
        }
    }

    /**
     * Convert Twilio μ-law audio to WAV format for Whisper API
     */
    public String convertTwilioToWav(String base64UlawAudio) {
        try {
            if (base64UlawAudio == null || base64UlawAudio.isEmpty()) {
                log.warn("Empty audio data provided for WAV conversion");
                return null;
            }

            // Decode base64 μ-law audio
            byte[] ulawData = Base64.getDecoder().decode(base64UlawAudio);
            
            // Convert μ-law to 16-bit PCM
            short[] pcmData = convertUlawToPcm(ulawData);
            
            // Convert PCM to byte array
            byte[] pcmBytes = convertPcmToBytes(pcmData);
            
            // Add WAV header
            byte[] wavData = addWavHeader(pcmBytes, sampleRate, bitDepth);
            
            return Base64.getEncoder().encodeToString(wavData);

        } catch (Exception e) {
            log.error("Failed to convert Twilio audio to WAV format", e);
            return null;
        }
    }

    /**
     * Convert audio data to Twilio format (handles MP3 from TTS)
     */
    public String convertToTwilio(String base64Audio) {
        try {
            if (base64Audio == null || base64Audio.isEmpty()) {
                log.warn("Empty audio data provided for Twilio conversion");
                return null;
            }

            // For MP3 from TTS, Twilio can handle it directly
            // In a production system, you might want to convert MP3 to μ-law
            // For now, return as-is since Twilio supports MP3 playback
            return base64Audio;

        } catch (Exception e) {
            log.error("Failed to convert audio to Twilio format", e);
            return null;
        }
    }

    /**
     * Convert PCM to WAV format with proper headers
     */
    public String convertPcmToWav(String base64PcmAudio, int sampleRate, int bitsPerSample) {
        try {
            byte[] pcmData = Base64.getDecoder().decode(base64PcmAudio);
            byte[] wavData = addWavHeader(pcmData, sampleRate, bitsPerSample);
            return Base64.getEncoder().encodeToString(wavData);
            
        } catch (Exception e) {
            log.error("Failed to convert PCM to WAV", e);
            return null;
        }
    }

    /**
     * Validate audio format and size
     */
    public boolean isValidAudioData(String base64Audio) {
        try {
            if (base64Audio == null || base64Audio.isEmpty()) {
                return false;
            }

            byte[] audioData = Base64.getDecoder().decode(base64Audio);
            
            // Check reasonable size limits (between 160 bytes and 10MB)
            return audioData.length >= 160 && audioData.length <= 10 * 1024 * 1024;

        } catch (Exception e) {
            log.debug("Invalid audio data format", e);
            return false;
        }
    }

    /**
     * Get audio duration estimate in milliseconds
     */
    public long estimateAudioDuration(String base64Audio, int sampleRate, int bitsPerSample) {
        try {
            byte[] audioData = Base64.getDecoder().decode(base64Audio);
            int bytesPerSample = bitsPerSample / 8;
            int totalSamples = audioData.length / bytesPerSample;
            return (totalSamples * 1000L) / sampleRate;
            
        } catch (Exception e) {
            log.debug("Failed to estimate audio duration", e);
            return 0;
        }
    }

    /**
     * Convert μ-law samples to 16-bit PCM
     */
    private short[] convertUlawToPcm(byte[] ulawData) {
        short[] pcmData = new short[ulawData.length];
        for (int i = 0; i < ulawData.length; i++) {
            pcmData[i] = ULAW_TO_LINEAR[ulawData[i] & 0xFF];
        }
        return pcmData;
    }

    /**
     * Convert 16-bit PCM samples to μ-law
     */
    private byte[] convertPcmToUlaw(short[] pcmData) {
        byte[] ulawData = new byte[pcmData.length];
        for (int i = 0; i < pcmData.length; i++) {
            ulawData[i] = LINEAR_TO_ULAW[pcmData[i] + 32768];
        }
        return ulawData;
    }

    /**
     * Convert PCM samples to byte array (little-endian)
     */
    private byte[] convertPcmToBytes(short[] pcmData) {
        ByteBuffer buffer = ByteBuffer.allocate(pcmData.length * 2);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : pcmData) {
            buffer.putShort(sample);
        }
        return buffer.array();
    }

    /**
     * Convert byte array to PCM samples (little-endian)
     */
    private short[] convertBytesToPcm(byte[] pcmBytes) {
        ByteBuffer buffer = ByteBuffer.wrap(pcmBytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        short[] pcmData = new short[pcmBytes.length / 2];
        for (int i = 0; i < pcmData.length; i++) {
            pcmData[i] = buffer.getShort();
        }
        return pcmData;
    }

    /**
     * Simple resampling (linear interpolation)
     */
    private short[] resample(short[] input, int inputRate, int outputRate) {
        if (inputRate == outputRate) {
            return input;
        }

        double ratio = (double) inputRate / outputRate;
        int outputLength = (int) (input.length / ratio);
        short[] output = new short[outputLength];

        for (int i = 0; i < outputLength; i++) {
            double sourceIndex = i * ratio;
            int index1 = (int) sourceIndex;
            int index2 = Math.min(index1 + 1, input.length - 1);
            
            double fraction = sourceIndex - index1;
            double sample1 = input[index1];
            double sample2 = input[index2];
            
            output[i] = (short) (sample1 + fraction * (sample2 - sample1));
        }

        return output;
    }

    /**
     * Add WAV header to PCM data
     */
    private byte[] addWavHeader(byte[] pcmData, int sampleRate, int bitsPerSample) {
        int dataLength = pcmData.length;
        int fileLength = dataLength + 36;
        int channels = 1; // Mono
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        byte[] header = new byte[44];
        
        // RIFF header
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        writeInt(header, 4, fileLength);
        
        // WAVE header
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        
        // fmt chunk
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        writeInt(header, 16, 16); // chunk size
        writeShort(header, 20, (short) 1); // PCM format
        writeShort(header, 22, (short) channels);
        writeInt(header, 24, sampleRate);
        writeInt(header, 28, byteRate);
        writeShort(header, 32, (short) blockAlign);
        writeShort(header, 34, (short) bitsPerSample);
        
        // data chunk
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        writeInt(header, 40, dataLength);
        
        // Combine header and data
        byte[] wavFile = new byte[header.length + pcmData.length];
        System.arraycopy(header, 0, wavFile, 0, header.length);
        System.arraycopy(pcmData, 0, wavFile, header.length, pcmData.length);
        
        return wavFile;
    }

    /**
     * Write 32-bit integer to byte array (little-endian)
     */
    private void writeInt(byte[] array, int offset, int value) {
        array[offset] = (byte) (value & 0xFF);
        array[offset + 1] = (byte) ((value >> 8) & 0xFF);
        array[offset + 2] = (byte) ((value >> 16) & 0xFF);
        array[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    /**
     * Write 16-bit short to byte array (little-endian)
     */
    private void writeShort(byte[] array, int offset, short value) {
        array[offset] = (byte) (value & 0xFF);
        array[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    /**
     * Convert μ-law byte to linear PCM sample
     */
    private static short ulawToLinear(byte ulaw) {
        ulaw = (byte) ~ulaw;
        int sign = ulaw & 0x80;
        int exponent = (ulaw >> 4) & 0x07;
        int mantissa = ulaw & 0x0F;
        
        int sample = ((((mantissa << 1) + 33) << exponent) - 33);
        if (sign != 0) {
            sample = -sample;
        }
        
        return (short) Math.max(-32768, Math.min(32767, sample));
    }

    /**
     * Convert linear PCM sample to μ-law byte
     */
    private static byte linearToUlaw(short linear) {
        int sample = linear;
        int sign = (sample < 0) ? 0x80 : 0x00;
        if (sample < 0) {
            sample = -sample;
        }
        
        sample += 33;
        
        int exponent = 7;
        int expMask = 0x4000;
        while ((sample & expMask) == 0 && exponent > 0) {
            exponent--;
            expMask >>= 1;
        }
        
        int mantissa = (sample >> (exponent + 3)) & 0x0F;
        int ulaw = sign | (exponent << 4) | mantissa;
        
        return (byte) ~ulaw;
    }

    /**
     * Normalize audio levels to prevent clipping
     */
    public String normalizeAudio(String base64Audio) {
        try {
            byte[] audioData = Base64.getDecoder().decode(base64Audio);
            short[] pcmData = convertBytesToPcm(audioData);
            
            // Find peak amplitude
            short peak = 0;
            for (short sample : pcmData) {
                peak = (short) Math.max(peak, Math.abs(sample));
            }
            
            // Normalize if needed
            if (peak > 0 && peak < 32767) {
                double scale = 32767.0 / peak * 0.95; // Leave some headroom
                for (int i = 0; i < pcmData.length; i++) {
                    pcmData[i] = (short) Math.max(-32768, Math.min(32767, pcmData[i] * scale));
                }
            }
            
            byte[] normalizedBytes = convertPcmToBytes(pcmData);
            return Base64.getEncoder().encodeToString(normalizedBytes);
            
        } catch (Exception e) {
            log.error("Failed to normalize audio", e);
            return base64Audio; // Return original on failure
        }
    }

    /**
     * Apply simple noise gate to reduce background noise
     */
    public String applyNoiseGate(String base64Audio, double threshold) {
        try {
            byte[] audioData = Base64.getDecoder().decode(base64Audio);
            short[] pcmData = convertBytesToPcm(audioData);
            
            short thresholdValue = (short) (32767 * threshold);
            
            for (int i = 0; i < pcmData.length; i++) {
                if (Math.abs(pcmData[i]) < thresholdValue) {
                    pcmData[i] = 0; // Silence below threshold
                }
            }
            
            byte[] processedBytes = convertPcmToBytes(pcmData);
            return Base64.getEncoder().encodeToString(processedBytes);
            
        } catch (Exception e) {
            log.error("Failed to apply noise gate", e);
            return base64Audio; // Return original on failure
        }
    }
}