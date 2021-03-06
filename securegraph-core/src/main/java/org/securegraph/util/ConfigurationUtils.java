package org.securegraph.util;

import org.securegraph.GraphConfiguration;
import org.securegraph.SecureGraphException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.Map;

public class ConfigurationUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationUtils.class);

    public static <T> T createProvider(GraphConfiguration config, String propPrefix, String defaultProvider) throws SecureGraphException {
        String implClass = config.getString(propPrefix, defaultProvider);
        return createProvider(implClass, config);
    }

    @SuppressWarnings("unchecked")
    public static <T> T createProvider(String className, GraphConfiguration config) throws SecureGraphException {
        LOGGER.debug("creating provider " + className);
        Class<GraphConfiguration> constructorParameterClass = GraphConfiguration.class;
        try {
            Class<?> clazz = Class.forName(className);
            try {
                Constructor constructor;
                try {
                    constructor = clazz.getConstructor(constructorParameterClass);
                    return (T) constructor.newInstance(config);
                } catch (NoSuchMethodException ignore) {
                    try {
                        constructor = clazz.getConstructor(Map.class);
                        return (T) constructor.newInstance(config.getConfig());
                    } catch (NoSuchMethodException ignoreInner) {
                        constructor = clazz.getConstructor();
                        return (T) constructor.newInstance();
                    }
                }
            } catch (IllegalArgumentException e) {
                StringBuilder possibleMatches = new StringBuilder();
                for (Constructor<?> s : clazz.getConstructors()) {
                    possibleMatches.append(s.toGenericString());
                    possibleMatches.append(", ");
                }
                throw new SecureGraphException("Invalid constructor for " + className + ". Expected <init>(" + constructorParameterClass.getName() + "). Found: " + possibleMatches, e);
            }
        } catch (NoSuchMethodException e) {
            throw new SecureGraphException("Provider must have a single argument constructor taking a " + constructorParameterClass.getName(), e);
        } catch (ClassNotFoundException e) {
            throw new SecureGraphException("No provider found with class name " + className, e);
        } catch (Exception e) {
            throw new SecureGraphException(e);
        }
    }
}
