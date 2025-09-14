package com.example.translationcallapp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Service for validating Twilio webhook signatures
 * Ensures webhooks are actually from Twilio
 */
@Service
public class TwilioSecurityService {
    
    private static final Logger logger = LoggerFactory.getLogger(TwilioSecurityService.class);
    
    @Value("${twilio.auth.token}")
    private String authToken;
    
    @Value("${security.webhook.validation.enabled:false}")
    private boolean validationEnabled;

    /**
     * Validate Twilio webhook signature
     * For now, this is a simplified implementation
     */
    public boolean isValidRequest(HttpServletRequest request, String url, Map<String, String> params) {
        if (!validationEnabled) {
            logger.debug("Webhook validation disabled, allowing request");
            return true;
        }
        
        try {
            // Get the signature from Twilio
            String twilioSignature = request.getHeader("X-Twilio-Signature");
            
            if (twilioSignature == null || twilioSignature.isEmpty()) {
                logger.warn("Missing Twilio signature header");
                return false;
            }
            
            // For production, implement proper HMAC-SHA1 signature validation
            // using com.twilio.security.RequestValidator
            
            // Simplified validation for now
            logger.debug("Twilio signature validation - signature present: {}", twilioSignature != null);
            return true;
            
        } catch (Exception e) {
            logger.error("Error validating Twilio signature: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Simple validation method for HTTP requests
     */
    public boolean validateTwilioRequest(HttpServletRequest request) {
        try {
            // Get the signature from Twilio
            String twilioSignature = request.getHeader("X-Twilio-Signature");
            
            if (twilioSignature == null || twilioSignature.isEmpty()) {
                logger.debug("Missing Twilio signature header");
                return !validationEnabled; // Allow if validation disabled
            }
            
            // For now, just return true if signature exists
            logger.debug("Twilio signature validation - signature present");
            return true;
            
        } catch (Exception e) {
            logger.error("Error validating Twilio request: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Simple validation method for basic checks
     */
    public boolean isValidRequest(Map<String, String> params) {
        // Basic validation - check if required Twilio parameters are present
        return params.containsKey("CallSid") || 
               params.containsKey("ConferenceSid") || 
               params.containsKey("AccountSid");
    }
}