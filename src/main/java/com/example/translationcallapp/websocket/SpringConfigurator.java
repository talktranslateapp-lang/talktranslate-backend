package com.example.translationcallapp.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import javax.websocket.server.ServerEndpointConfig;

@Component
@Slf4j
public class SpringConfigurator extends ServerEndpointConfig.Configurator implements ApplicationContextAware {

    private static volatile BeanFactory beanFactory;

    @Override
    public <T> T getEndpointInstance(Class<T> clazz) throws InstantiationException {
        try {
            // Null check as recommended by Twilio
            if (beanFactory == null) {
                log.error("Spring BeanFactory not initialized for WebSocket endpoint: {}", clazz.getSimpleName());
                throw new InstantiationException("Spring context not available for WebSocket endpoint creation");
            }

            // Get bean from Spring context with proper error handling
            T endpoint = beanFactory.getBean(clazz);
            log.debug("Created WebSocket endpoint instance: {}", clazz.getSimpleName());
            return endpoint;
            
        } catch (NoSuchBeanDefinitionException e) {
            log.error("No bean definition found for WebSocket endpoint: {}", clazz.getSimpleName(), e);
            throw new InstantiationException("No Spring bean found for endpoint class: " + clazz.getSimpleName());
            
        } catch (BeansException e) {
            log.error("Error creating WebSocket endpoint bean: {}", clazz.getSimpleName(), e);
            throw new InstantiationException("Failed to create endpoint bean: " + e.getMessage());
            
        } catch (Exception e) {
            log.error("Unexpected error creating WebSocket endpoint: {}", clazz.getSimpleName(), e);
            throw new InstantiationException("Unexpected error: " + e.getMessage());
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        try {
            SpringConfigurator.beanFactory = applicationContext;
            log.info("Spring ApplicationContext set for WebSocket configurator");
            
        } catch (Exception e) {
            log.error("Error setting ApplicationContext for WebSocket configurator", e);
            throw e;
        }
    }

    /**
     * Check if Spring context is available (for testing/monitoring)
     */
    public static boolean isContextAvailable() {
        return beanFactory != null;
    }

    /**
     * Get the current bean factory (for testing purposes)
     */
    static BeanFactory getBeanFactory() {
        return beanFactory;
    }
}