package com.catalogue.verg.core.util;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Loads and caches properties from files, with environment variable overrides. */
public class PropertiesCache {
    // Logger for logging messages
    private final Logger logger = LogManager.getLogger(getClass());

    // Array of file names from which properties are loaded
    private final String[] fileName = {
            "application.properties",
            "customerror.properties"
    };
    // Properties object to store loaded properties
    private final Properties configProp = new Properties();

    /** Private constructor to prevent instantiation from outside. */
    private PropertiesCache() {
        // Load properties from each file
        for (String file : fileName) {
            InputStream in = this.getClass().getClassLoader().getResourceAsStream(file);
            try {
                configProp.load(in);
            } catch (IOException e) {
            }
        }
    }

    /** The singleton instance of PropertiesCache. */
    public static PropertiesCache getInstance() {

        // change the lazy holder implementation to simple singleton implementation ...
        return PropertiesCacheHolder.propertiesCache;
    }

    private static final class PropertiesCacheHolder {
        static final PropertiesCache propertiesCache = new PropertiesCache();
    }

    /** A property value: env var first, then the files, else the key itself. */
    public String getProperty(String key) {
        String value = System.getenv(key);
        if (StringUtils.isNotBlank(value)) return value;
        return configProp.getProperty(key) != null ? configProp.getProperty(key) : key;
    }

    /** A property value: env var first, then the files, else null. */
    public String readProperty(String key) {
        String value = System.getenv(key);
        if (StringUtils.isNotBlank(value)) return value;
        return configProp.getProperty(key);
    }
}