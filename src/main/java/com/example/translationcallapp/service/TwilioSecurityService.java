package com.example.translationcallapp.service;

import com.twilio.security.RequestValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

@Service
public class TwilioSecurityService {

    private static final Logger logger = LoggerFactory.getLogger(TwilioSecurityService.class);

    @Value("${twilio.auth.token}")
    private String authToken;

    private RequestValidator requestValidator;

    /**
     * Validates that a request came from Twilio
     */
    public boolean validateTwilioRequest(HttpServletRequest request) {
        try {
            if (requestValidator == null) {
                requestValidator = new RequestValidator(authToken);
            }

            String url = getFullURL(request);
            Map<String, String> params = getParameterMap(request);
            String signature = request.getHeader("X-Twilio-Signature");

            if (signature == null) {
                logger.warn("Missing X-Twilio-Signature header");
                return false;
            }

            boolean isValid = requestValidator.validate(url, params, signature);
            
            if (!isValid) {
                logger.warn("Invalid Twilio signature for URL: {}", url);
            }

            return isValid;

        } catch (Exception e) {
            logger.error("Error validating Twilio request: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Gets the full URL from the request
     */
    private String getFullURL(HttpServletRequest request) {
        StringBuilder requestURL = new StringBuilder(request.getRequestURL().toString());
        String queryString = request.getQueryString();

        if (queryString == null) {
            return requestURL.toString();
        } else {
            return requestURL.append('?').append(queryString).toString();
        }
    }

    /**
     * Converts request parameters to a Map
     */
    private Map<String, String> getParameterMap(HttpServletRequest request) {
        Map<String, String> params = new HashMap<>();
        
        // Handle form parameters
        Enumeration<String> parameterNames = request.getParameterNames();
        while (parameterNames.hasMoreElements()) {
            String paramName = parameterNames.nextElement();
            String paramValue = request.getParameter(paramName);
            params.put(paramName, paramValue);
        }

        return params;
    }

    /**
     * Sanitizes parameter values for logging (removes sensitive data)
     */
    public Map<String, String> sanitizeParameters(Map<String, String> params) {
        Map<String, String> sanitized = new HashMap<>(params);
        
        // Remove or mask sensitive parameters
        sanitized.remove("AuthToken");
        sanitized.remove("AccountSid");
        
        if (sanitized.containsKey("Digits")) {
            String digits = sanitized.get("Digits");
            if (digits != null && digits.length() > 4) {
                sanitized.put("Digits", "****" + digits.substring(digits.length() - 4));
            }
        }

        return sanitized;
    }

    /**
     * Generates secure webhook tokens for authentication
     */
    public String generateWebhookToken(String data) {
        try {
            // Add timestamp for time-based validation
            String timestampedData = data + ":" + (System.currentTimeMillis() / 1000); // Unix timestamp
            
            // Create SHA-256 hash
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((timestampedData + ":" + authToken).getBytes("UTF-8"));
            
            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            return hexString.toString().substring(0, 32); // Truncate to 32 characters
            
        } catch (Exception e) {
            logger.error("Error generating webhook token: {}", e.getMessage(), e);
            return "fallback-token-" + System.currentTimeMillis();
        }
    }

    /**
     * Validates webhook tokens with time-based expiry
     */
    public boolean validateWebhookToken(String data, String token, int maxAgeMinutes) {
        try {
            long currentTime = System.currentTimeMillis() / 1000;
            
            // Try different timestamps within the allowed window
            for (int i = 0; i <= maxAgeMinutes; i++) {
                long testTime = currentTime - (i * 60); // Go back i minutes
                String expectedToken = generateTokenForTimestamp(data, testTime);
                
                if (expectedToken.equals(token)) {
                    return true;
                }
            }
            
            return false;
            
        } catch (Exception e) {
            logger.error("Error validating webhook token: {}", e.getMessage(), e);
            return false;
        }
    }

    private String generateTokenForTimestamp(String data, long timestamp) {
        try {
            String timestampedData = data + ":" + timestamp;
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((timestampedData + ":" + authToken).getBytes("UTF-8"));
            
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            return hexString.toString().substring(0, 32);
            
        } catch (Exception e) {
            logger.error("Error generating token for timestamp: {}", e.getMessage(), e);
            return "fallback-token";
        }
    }
}