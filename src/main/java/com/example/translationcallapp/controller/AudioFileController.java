package com.example.translationcallapp.controller;

import com.example.translationcallapp.service.AudioFileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/audio")
@CrossOrigin(
    origins = {
        "${app.frontend-url:https://talktranslate.netlify.app}",
        "http://localhost:3000",
        "http://localhost:5173",
        "https://*.netlify.app"
    },
    allowCredentials = true,
    maxAge = 3600
)
@Slf4j
public class AudioFileController {

    @Autowired
    private AudioFileStorageService audioFileStorageService;

    // Security: Whitelist pattern for filenames - only alphanumeric, dots, hyphens, underscores
    private static final Pattern VALID_FILENAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+$");
    
    // Maximum filename length to prevent long filename attacks
    private static final int MAX_FILENAME_LENGTH = 255;

    @Value("${audio.download.mode:inline}")
    private String downloadMode; // "inline" or "attachment"

    /**
     * Serve translated audio files to Twilio with enhanced security
     */
    @GetMapping("/translated/{filename}")
    public ResponseEntity<Resource> getTranslatedAudio(@PathVariable String filename) {
        try {
            log.debug("Request for translated audio file: {}", filename);

            // Security: Validate filename to prevent path traversal attacks
            if (!isValidFilename(filename)) {
                log.warn("Invalid filename requested: {}", filename);
                return ResponseEntity.badRequest().build();
            }

            // Get the audio file resource
            Resource audioResource = audioFileStorageService.getAudioResource(filename);
            
            if (audioResource == null || !audioResource.exists()) {
                log.warn("Audio file not found: {}", filename);
                return ResponseEntity.notFound().build();
            }

            // Determine content type with enhanced detection
            MediaType mediaType = determineMediaType(filename, audioResource);
            
            // Get file size for Content-Length header
            long contentLength = audioResource.contentLength();

            log.debug("Serving audio file: {}, size: {} bytes, type: {}", 
                     filename, contentLength, mediaType);

            // Build response with security headers
            ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok()
                    .contentType(mediaType)
                    .contentLength(contentLength)
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(HttpHeaders.EXPIRES, "0")
                    .header("X-Content-Type-Options", "nosniff")
                    .header("X-Frame-Options", "DENY");

            // Add Content-Disposition based on configuration
            if ("attachment".equals(downloadMode)) {
                responseBuilder.header(HttpHeaders.CONTENT_DISPOSITION, 
                    "attachment; filename=\"" + sanitizeFilenameForHeader(filename) + "\"");
            } else {
                responseBuilder.header(HttpHeaders.CONTENT_DISPOSITION, 
                    "inline; filename=\"" + sanitizeFilenameForHeader(filename) + "\"");
            }

            return responseBuilder.body(audioResource);

        } catch (Exception e) {
            log.error("Error serving audio file: {}", filename, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Serve audio files with conference and direction context
     */
    @GetMapping("/conference/{conferenceName}/{direction}/{filename}")
    public ResponseEntity<Resource> getConferenceAudio(
            @PathVariable String conferenceName,
            @PathVariable String direction,
            @PathVariable String filename) {
        try {
            log.debug("Request for conference audio - Conference: {}, Direction: {}, File: {}", 
                     conferenceName, direction, filename);

            // Security: Validate all path parameters
            if (!isValidFilename(filename) || !isValidConferenceName(conferenceName) || !isValidDirection(direction)) {
                log.warn("Invalid parameters - Conference: {}, Direction: {}, File: {}", 
                        conferenceName, direction, filename);
                return ResponseEntity.badRequest().build();
            }

            // Get the audio file resource with conference context
            Resource audioResource = audioFileStorageService.getConferenceAudioResource(
                conferenceName, direction, filename);
            
            if (audioResource == null || !audioResource.exists()) {
                log.warn("Conference audio file not found - Conference: {}, Direction: {}, File: {}", 
                        conferenceName, direction, filename);
                return ResponseEntity.notFound().build();
            }

            MediaType mediaType = determineMediaType(filename, audioResource);
            long contentLength = audioResource.contentLength();

            log.debug("Serving conference audio file: {}, size: {} bytes", filename, contentLength);

            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .contentLength(contentLength)
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(HttpHeaders.EXPIRES, "0")
                    .header("X-Content-Type-Options", "nosniff")
                    .header("X-Frame-Options", "DENY")
                    .header("X-Conference-Name", sanitizeHeaderValue(conferenceName))
                    .header("X-Translation-Direction", sanitizeHeaderValue(direction))
                    .body(audioResource);

        } catch (Exception e) {
            log.error("Error serving conference audio file - Conference: {}, Direction: {}, File: {}", 
                     conferenceName, direction, filename, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Health check endpoint for audio service
     */
    @GetMapping("/health")
    public ResponseEntity<AudioServiceHealth> healthCheck() {
        try {
            boolean isHealthy = audioFileStorageService.isServiceHealthy();
            int activeFiles = audioFileStorageService.getActiveFileCount();
            double storageUsed = audioFileStorageService.getStorageUsedMB();
            
            AudioServiceHealth health = new AudioServiceHealth(
                isHealthy ? "UP" : "DOWN",
                activeFiles,
                storageUsed,
                System.currentTimeMillis()
            );
            
            if (isHealthy) {
                return ResponseEntity.ok(health);
            } else {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(health);
            }
            
        } catch (Exception e) {
            log.error("Error in audio service health check", e);
            AudioServiceHealth health = new AudioServiceHealth("ERROR", 0, 0.0, System.currentTimeMillis());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(health);
        }
    }

    /**
     * Get audio service statistics (protected endpoint)
     */
    @GetMapping("/stats")
    public ResponseEntity<AudioServiceStats> getAudioStats() {
        try {
            AudioServiceStats stats = new AudioServiceStats();
            stats.setActiveFiles(audioFileStorageService.getActiveFileCount());
            stats.setTotalFilesServed(audioFileStorageService.getTotalFilesServed());
            stats.setStorageUsed(audioFileStorageService.getStorageUsedMB());
            
            return ResponseEntity.ok(stats);
            
        } catch (Exception e) {
            log.error("Error getting audio service statistics", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Manual cleanup endpoint (for debugging/maintenance)
     */
    @PostMapping("/cleanup")
    public ResponseEntity<CleanupResult> manualCleanup() {
        try {
            log.info("Manual audio file cleanup requested");
            
            int cleanedFiles = audioFileStorageService.performManualCleanup();
            
            CleanupResult result = new CleanupResult(cleanedFiles, System.currentTimeMillis());
            log.info("Manual cleanup completed: {} files removed", cleanedFiles);
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("Error during manual cleanup", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new CleanupResult(0, System.currentTimeMillis()));
        }
    }

    /**
     * Security: Validate filename to prevent path traversal attacks
     */
    private boolean isValidFilename(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return false;
        }
        
        if (filename.length() > MAX_FILENAME_LENGTH) {
            return false;
        }
        
        // Check for path traversal attempts
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return false;
        }
        
        // Must match our whitelist pattern
        return VALID_FILENAME_PATTERN.matcher(filename).matches();
    }

    /**
     * Security: Validate conference name
     */
    private boolean isValidConferenceName(String conferenceName) {
        if (conferenceName == null || conferenceName.trim().isEmpty()) {
            return false;
        }
        
        // Conference names should be alphanumeric with hyphens
        return conferenceName.matches("^[a-zA-Z0-9-]+$") && conferenceName.length() <= 100;
    }

    /**
     * Security: Validate direction parameter
     */
    private boolean isValidDirection(String direction) {
        return "caller-to-target".equals(direction) || "target-to-caller".equals(direction);
    }

    /**
     * Sanitize filename for use in Content-Disposition header
     */
    private String sanitizeFilenameForHeader(String filename) {
        // Remove any characters that could cause header injection
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * Sanitize values for use in custom headers
     */
    private String sanitizeHeaderValue(String value) {
        // Remove any characters that could cause header injection
        return value.replaceAll("[\\r\\n\\t]", "");
    }

    /**
     * Enhanced media type determination with file probing
     */
    private MediaType determineMediaType(String filename, Resource resource) {
        if (filename == null) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        
        String lowerFilename = filename.toLowerCase();
        
        // Try file extension first
        if (lowerFilename.endsWith(".wav")) {
            return MediaType.parseMediaType("audio/wav");
        } else if (lowerFilename.endsWith(".mp3")) {
            return MediaType.parseMediaType("audio/mpeg");
        } else if (lowerFilename.endsWith(".ogg")) {
            return MediaType.parseMediaType("audio/ogg");
        } else if (lowerFilename.endsWith(".m4a")) {
            return MediaType.parseMediaType("audio/mp4");
        }
        
        // Try to probe content type from file content
        try {
            if (resource.getFile() != null) {
                Path path = resource.getFile().toPath();
                String contentType = Files.probeContentType(path);
                if (contentType != null && contentType.startsWith("audio/")) {
                    return MediaType.parseMediaType(contentType);
                }
            }
        } catch (Exception e) {
            log.debug("Could not probe content type for file: {}", filename);
        }
        
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /**
     * Audio service health DTO
     */
    public static class AudioServiceHealth {
        private final String status;
        private final int activeFiles;
        private final double storageUsedMB;
        private final long timestamp;

        public AudioServiceHealth(String status, int activeFiles, double storageUsedMB, long timestamp) {
            this.status = status;
            this.activeFiles = activeFiles;
            this.storageUsedMB = storageUsedMB;
            this.timestamp = timestamp;
        }

        public String getStatus() { return status; }
        public int getActiveFiles() { return activeFiles; }
        public double getStorageUsedMB() { return storageUsedMB; }
        public long getTimestamp() { return timestamp; }
    }

    /**
     * Audio service statistics DTO
     */
    public static class AudioServiceStats {
        private int activeFiles;
        private long totalFilesServed;
        private double storageUsed;

        // Getters and setters
        public int getActiveFiles() { return activeFiles; }
        public void setActiveFiles(int activeFiles) { this.activeFiles = activeFiles; }
        
        public long getTotalFilesServed() { return totalFilesServed; }
        public void setTotalFilesServed(long totalFilesServed) { this.totalFilesServed = totalFilesServed; }
        
        public double getStorageUsed() { return storageUsed; }
        public void setStorageUsed(double storageUsed) { this.storageUsed = storageUsed; }
    }

    /**
     * Cleanup result DTO
     */
    public static class CleanupResult {
        private final int filesRemoved;
        private final long timestamp;

        public CleanupResult(int filesRemoved, long timestamp) {
            this.filesRemoved = filesRemoved;
            this.timestamp = timestamp;
        }

        public int getFilesRemoved() { return filesRemoved; }
        public long getTimestamp() { return timestamp; }
    }
}