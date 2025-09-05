package com.example.translationcallapp.controller;

import com.example.translationcallapp.service.AudioFileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/audio")
@CrossOrigin(origins = "*", maxAge = 3600)
public class AudioFileController {

    private final AudioFileStorageService audioFileStorageService;

    @Autowired
    public AudioFileController(AudioFileStorageService audioFileStorageService) {
        this.audioFileStorageService = audioFileStorageService;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadAudioFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "conferenceId", required = false) String conferenceId,
            @RequestParam(value = "direction", defaultValue = "inbound") String direction,
            @RequestParam(value = "includeTranscript", defaultValue = "false") boolean includeTranscript,
            @RequestParam(value = "language", defaultValue = "en") String language) {
        
        try {
            log.info("Received audio file upload request - File: {}, Conference: {}, Direction: {}", 
                    file.getOriginalFilename(), conferenceId, direction);

            if (file.isEmpty()) {
                log.warn("Upload failed: Empty file received");
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "File is empty", "success", false));
            }

            if (file.getSize() > 50 * 1024 * 1024) { // 50MB limit
                log.warn("Upload failed: File too large - {} bytes", file.getSize());
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "File size exceeds 50MB limit", "success", false));
            }

            String fileId = audioFileStorageService.storeAudioFile(file, conferenceId, direction);
            log.info("Audio file stored successfully with ID: {}", fileId);

            Map<String, Object> response = Map.of(
                    "success", true,
                    "fileId", fileId,
                    "message", "File uploaded successfully",
                    "filename", file.getOriginalFilename(),
                    "size", file.getSize()
            );

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("IO error during file upload: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to upload file: " + e.getMessage(), "success", false));
        } catch (Exception e) {
            log.error("Unexpected error during file upload: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error", "success", false));
        }
    }

    @GetMapping("/download/{fileId}")
    public ResponseEntity<Resource> downloadAudioFile(
            @PathVariable String fileId,
            HttpServletRequest request) {
        
        try {
            log.info("Download request for file ID: {}", fileId);

            Resource resource = audioFileStorageService.loadAudioFileAsResource(fileId);
            
            if (resource == null || !resource.exists()) {
                log.warn("File not found for ID: {}", fileId);
                return ResponseEntity.notFound().build();
            }

            // Determine content type
            String contentType = null;
            try {
                contentType = request.getServletContext().getMimeType(resource.getFile().getAbsolutePath());
            } catch (IOException ex) {
                log.debug("Could not determine file type for file ID: {}", fileId);
            }

            if (contentType == null) {
                contentType = "application/octet-stream";
            }

            String filename = resource.getFilename();
            log.info("Serving file: {} with content type: {}", filename, contentType);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, 
                           "attachment; filename=\"" + filename + "\"")
                    .body(resource);

        } catch (Exception e) {
            log.error("Error downloading file with ID {}: {}", fileId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/stream/{fileId}")
    public ResponseEntity<Resource> streamAudioFile(@PathVariable String fileId) {
        try {
            log.info("Stream request for file ID: {}", fileId);

            Resource resource = audioFileStorageService.loadAudioFileAsResource(fileId);
            
            if (resource == null || !resource.exists()) {
                log.warn("File not found for streaming, ID: {}", fileId);
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .body(resource);

        } catch (Exception e) {
            log.error("Error streaming file with ID {}: {}", fileId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/list")
    public ResponseEntity<?> listAudioFiles(
            @RequestParam(value = "conferenceId", required = false) String conferenceId,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        
        try {
            log.info("List audio files request - Conference: {}, Limit: {}, Offset: {}", 
                    conferenceId, limit, offset);

            List<Map<String, Object>> files = audioFileStorageService.listAudioFiles(
                    conferenceId, limit, offset);

            Map<String, Object> response = Map.of(
                    "success", true,
                    "files", files,
                    "count", files.size(),
                    "limit", limit,
                    "offset", offset
            );

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error listing audio files: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to list files", "success", false));
        }
    }

    @DeleteMapping("/{fileId}")
    public ResponseEntity<?> deleteAudioFile(@PathVariable String fileId) {
        try {
            log.info("Delete request for file ID: {}", fileId);

            boolean deleted = audioFileStorageService.deleteAudioFile(fileId);
            
            if (deleted) {
                log.info("File deleted successfully: {}", fileId);
                return ResponseEntity.ok(Map.of("success", true, "message", "File deleted successfully"));
            } else {
                log.warn("File not found for deletion: {}", fileId);
                return ResponseEntity.notFound().build();
            }

        } catch (Exception e) {
            log.error("Error deleting file with ID {}: {}", fileId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete file", "success", false));
        }
    }

    @GetMapping("/info/{fileId}")
    public ResponseEntity<?> getAudioFileInfo(@PathVariable String fileId) {
        try {
            log.info("Info request for file ID: {}", fileId);

            Map<String, Object> fileInfo = audioFileStorageService.getAudioFileInfo(fileId);
            
            if (fileInfo != null) {
                return ResponseEntity.ok(Map.of("success", true, "fileInfo", fileInfo));
            } else {
                log.warn("File info not found for ID: {}", fileId);
                return ResponseEntity.notFound().build();
            }

        } catch (Exception e) {
            log.error("Error getting file info for ID {}: {}", fileId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get file info", "success", false));
        }
    }

    @PostMapping("/process/{fileId}")
    public ResponseEntity<?> processAudioFile(
            @PathVariable String fileId,
            @RequestParam(value = "operation", defaultValue = "transcribe") String operation,
            @RequestParam(value = "targetLanguage", required = false) String targetLanguage) {
        
        try {
            log.info("Process request for file ID: {} with operation: {}", fileId, operation);

            Map<String, Object> result = audioFileStorageService.processAudioFile(
                    fileId, operation, targetLanguage);
            
            if (result != null) {
                return ResponseEntity.ok(Map.of("success", true, "result", result));
            } else {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Processing failed", "success", false));
            }

        } catch (Exception e) {
            log.error("Error processing file with ID {}: {}", fileId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Processing failed: " + e.getMessage(), "success", false));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<?> getStorageStatus() {
        try {
            Map<String, Object> status = audioFileStorageService.getStorageStatus();
            return ResponseEntity.ok(Map.of("success", true, "status", status));
        } catch (Exception e) {
            log.error("Error getting storage status: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get status", "success", false));
        }
    }

    @PostMapping("/cleanup")
    public ResponseEntity<?> cleanupOldFiles(
            @RequestParam(value = "olderThanDays", defaultValue = "7") int olderThanDays) {
        
        try {
            log.info("Cleanup request for files older than {} days", olderThanDays);

            int deletedCount = audioFileStorageService.cleanupOldFiles(olderThanDays);
            
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Cleanup completed",
                    "deletedFiles", deletedCount
            ));

        } catch (Exception e) {
            log.error("Error during cleanup: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Cleanup failed", "success", false));
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleException(Exception e) {
        log.error("Unhandled exception in AudioFileController: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error", "success", false));
    }
}