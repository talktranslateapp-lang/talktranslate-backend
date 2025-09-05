package com.example.translationcallapp.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import javax.websocket.server.ServerEndpointConfig;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class SpringConfigurator extends ServerEndpointConfig.Configurator implements ApplicationContextAware {

    private static volatile ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        log.info("Setting Spring ApplicationContext in SpringConfigurator");
        SpringConfigurator.context = applicationContext;
        log.info("ApplicationContext set successfully");
    }

    @Override
    public <T> T getEndpointInstance(Class<T> clazz) throws InstantiationException {
        log.debug("Creating endpoint instance for class: {}", clazz.getName());
        
        try {
            if (context == null) {
                log.warn("ApplicationContext is null, creating instance manually");
                return clazz.getDeclaredConstructor().newInstance();
            }

            // Get bean from Spring context
            T endpoint = context.getBean(clazz);
            log.debug("Successfully created endpoint instance from Spring context: {}", clazz.getName());
            return endpoint;
            
        } catch (Exception e) {
            log.error("Failed to create endpoint instance for {}: {}", clazz.getName(), e.getMessage(), e);
            
            // Fallback to manual instantiation
            try {
                log.info("Attempting manual instantiation for: {}", clazz.getName());
                T instance = clazz.getDeclaredConstructor().newInstance();
                log.info("Manual instantiation successful for: {}", clazz.getName());
                return instance;
            } catch (Exception fallbackException) {
                log.error("Manual instantiation also failed for {}: {}", 
                         clazz.getName(), fallbackException.getMessage(), fallbackException);
                throw new InstantiationException("Failed to create instance: " + fallbackException.getMessage());
            }
        }
    }

    @Override
    public boolean checkOrigin(String originHeaderValue) {
        log.debug("Checking origin: {}", originHeaderValue);
        
        // In production, implement proper origin validation
        // For development, allow all origins
        boolean allowed = true; // TODO: Implement proper origin checking
        
        if (!allowed) {
            log.warn("Origin not allowed: {}", originHeaderValue);
        } else {
            log.debug("Origin allowed: {}", originHeaderValue);
        }
        
        return allowed;
    }

    @Override
    public void modifyHandshake(ServerEndpointConfig sec, 
                              javax.websocket.HandshakeRequest request,
                              javax.websocket.HandshakeResponse response) {
        try {
            log.debug("Modifying WebSocket handshake");
            
            // Log request details for debugging
            String requestURI = request.getRequestURI().toString();
            Map<String, List<String>> headers = request.getHeaders();
            
            log.debug("WebSocket handshake - URI: {}", requestURI);
            log.debug("WebSocket handshake - Headers: {}", headers.keySet());
            
            // Add custom headers to response if needed
            response.getHeaders().put("Access-Control-Allow-Origin", Collections.singletonList("*"));
            response.getHeaders().put("Access-Control-Allow-Methods", 
                                    Collections.singletonList("GET, POST, OPTIONS"));
            response.getHeaders().put("Access-Control-Allow-Headers", 
                                    Collections.singletonList("Content-Type, Authorization"));
            
            // Store request information in user properties if needed
            sec.getUserProperties().put("requestURI", requestURI);
            sec.getUserProperties().put("remoteAddr", getRemoteAddress(request));
            
            log.debug("WebSocket handshake modification completed");
            
        } catch (Exception e) {
            log.error("Error during handshake modification: {}", e.getMessage(), e);
            // Don't throw exception here as it would fail the handshake
        }
    }

    @Override
    public List<String> getNegotiatedSubprotocols(List<String> supported, List<String> requested) {
        log.debug("Negotiating subprotocols - Supported: {}, Requested: {}", supported, requested);
        
        // Return the first matching subprotocol
        for (String requestedProtocol : requested) {
            if (supported.contains(requestedProtocol)) {
                log.debug("Subprotocol negotiated: {}", requestedProtocol);
                return Collections.singletonList(requestedProtocol);
            }
        }
        
        log.debug("No subprotocol negotiated");
        return Collections.emptyList();
    }

    @Override
    public List<javax.websocket.Extension> getNegotiatedExtensions(
            List<javax.websocket.Extension> installed, 
            List<javax.websocket.Extension> requested) {
        
        log.debug("Negotiating extensions - Installed: {}, Requested: {}", 
                 installed.size(), requested.size());
        
        // For now, return empty list (no extensions)
        // In the future, you might want to support compression or other extensions
        return Collections.emptyList();
    }

    /**
     * Get the remote address from the handshake request
     */
    private String getRemoteAddress(javax.websocket.HandshakeRequest request) {
        try {
            // Try to get real IP from headers (in case of proxy/load balancer)
            Map<String, List<String>> headers = request.getHeaders();
            
            // Check X-Forwarded-For header first
            List<String> xForwardedFor = headers.get("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
                String forwardedIp = xForwardedFor.get(0);
                if (forwardedIp.contains(",")) {
                    // Take the first IP in the chain
                    forwardedIp = forwardedIp.split(",")[0].trim();
                }
                return forwardedIp;
            }
            
            // Check X-Real-IP header
            List<String> xRealIp = headers.get("X-Real-IP");
            if (xRealIp != null && !xRealIp.isEmpty()) {
                return xRealIp.get(0);
            }
            
            // Fallback to direct connection (this might not work in all servlet containers)
            return "unknown";
            
        } catch (Exception e) {
            log.warn("Could not determine remote address: {}", e.getMessage());
            return "unknown";
        }
    }

    /**
     * Utility method to get ApplicationContext
     */
    public static ApplicationContext getApplicationContext() {
        return context;
    }

    /**
     * Utility method to get a bean from the context
     */
    public static <T> T getBean(Class<T> beanClass) {
        if (context == null) {
            log.error("ApplicationContext is null, cannot retrieve bean: {}", beanClass.getName());
            return null;
        }
        
        try {
            return context.getBean(beanClass);
        } catch (BeansException e) {
            log.error("Failed to retrieve bean {}: {}", beanClass.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * Utility method to get a bean by name
     */
    public static Object getBean(String beanName) {
        if (context == null) {
            log.error("ApplicationContext is null, cannot retrieve bean: {}", beanName);
            return null;
        }
        
        try {
            return context.getBean(beanName);
        } catch (BeansException e) {
            log.error("Failed to retrieve bean {}: {}", beanName, e.getMessage());
            return null;
        }
    }

    /**
     * Check if the configurator is properly initialized
     */
    public static boolean isInitialized() {
        return context != null;
    }

    /**
     * Get bean factory for advanced usage
     */
    public static BeanFactory getBeanFactory() {
        return context;
    }
}