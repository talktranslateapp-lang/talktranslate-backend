package com.example.translationcallapp.websocket;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import jakarta.websocket.server.ServerEndpointConfig;
import jakarta.websocket.HandshakeRequest;
import jakarta.websocket.server.HandshakeRequest;

/**
 * Configurator for WebSocket endpoints that enables Spring dependency injection
 * in WebSocket endpoint classes.
 */
@Component
public class SpringConfigurator extends ServerEndpointConfig.Configurator implements ApplicationContextAware {

    private static volatile BeanFactory context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        SpringConfigurator.context = applicationContext;
    }

    @Override
    public <T> T getEndpointInstance(Class<T> clazz) throws InstantiationException {
        return context.getBean(clazz);
    }

    /**
     * Called before the handshake response is sent to allow modification of headers.
     * This can be used for authentication, CORS, or other security measures.
     */
    @Override
    public void modifyHandshake(ServerEndpointConfig sec, 
                              HandshakeRequest request, 
                              jakarta.websocket.server.HandshakeResponse response) {
        // Add any custom handshake logic here
        // For example, authentication, CORS headers, etc.
        
        // Store HTTP session in WebSocket session if needed
        jakarta.servlet.http.HttpSession httpSession = 
            (jakarta.servlet.http.HttpSession) request.getHttpSession();
        if (httpSession != null) {
            sec.getUserProperties().put("httpSession", httpSession);
        }
        
        // Store request headers for potential use
        sec.getUserProperties().put("headers", request.getHeaders());
        
        // Store remote address
        sec.getUserProperties().put("remoteAddress", 
            request.getHeaders().get("x-forwarded-for"));
    }

    /**
     * Determines if the origin of the WebSocket handshake request is acceptable.
     * This is important for security to prevent unauthorized cross-origin requests.
     */
    @Override
    public boolean checkOrigin(String originHeaderValue) {
        // In production, you should validate against allowed origins
        // For now, allowing all origins for development
        return true;
        
        // Production example:
        // List<String> allowedOrigins = Arrays.asList(
        //     "https://yourdomain.com",
        //     "https://www.yourdomain.com",
        //     "https://app.yourdomain.com"
        // );
        // return allowedOrigins.contains(originHeaderValue);
    }

    /**
     * Allows modification of the subprotocol selection process.
     */
    @Override
    public String getNegotiatedSubprotocol(java.util.List<String> supported, 
                                         java.util.List<String> requested) {
        // Return the first matching subprotocol
        for (String requestedProtocol : requested) {
            if (supported.contains(requestedProtocol)) {
                return requestedProtocol;
            }
        }
        return "";
    }

    /**
     * Allows modification of the extension negotiation process.
     */
    @Override
    public java.util.List<jakarta.websocket.Extension> getNegotiatedExtensions(
            java.util.List<jakarta.websocket.Extension> installed, 
            java.util.List<jakarta.websocket.Extension> requested) {
        // Return negotiated extensions
        return new java.util.ArrayList<>();
    }
}