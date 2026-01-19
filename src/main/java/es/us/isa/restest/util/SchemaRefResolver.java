package es.us.isa.restest.util;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/**
 * Utility class to resolve schema references that use placeholder prefixes like "api_"
 * to the actual schema names that include service name prefixes.
 * 
 * For example: "api_TravelInfo" with service "ts-admin-travel-service" 
 * becomes "ts-admin-travel-service_TravelInfo"
 */
public class SchemaRefResolver {
    
    private static final Logger logger = LogManager.getLogger(SchemaRefResolver.class);
    private static final String API_PREFIX = "api_";
    private static final String ACTUATOR_PREFIX = "actuator_";
    private static final String SERVICE_NAME_EXTENSION = "x-service-name";
    
    // Thread-local storage for current service name context
    private static final ThreadLocal<String> currentServiceName = new ThreadLocal<>();
    
    private SchemaRefResolver() {}
    
    /**
     * Set the current service name context for schema resolution
     */
    public static void setCurrentServiceName(String serviceName) {
        currentServiceName.set(serviceName);
    }
    
    /**
     * Get the current service name context
     */
    public static String getCurrentServiceName() {
        return currentServiceName.get();
    }
    
    /**
     * Clear the current service name context
     */
    public static void clearCurrentServiceName() {
        currentServiceName.remove();
    }
    
    /**
     * Extract service name from an operation's x-service-name extension
     */
    public static String getServiceNameFromOperation(Operation operation) {
        if (operation != null && operation.getExtensions() != null) {
            Object serviceName = operation.getExtensions().get(SERVICE_NAME_EXTENSION);
            if (serviceName != null) {
                return serviceName.toString();
            }
        }
        return null;
    }
    
    /**
     * Resolve a schema reference that may use the "api_" prefix to the actual schema name.
     * 
     * @param schemaRef The original schema reference (e.g., "#/components/schemas/api_TravelInfo")
     * @param serviceName The service name to use as prefix (e.g., "ts-admin-travel-service")
     * @param spec The OpenAPI specification
     * @return The resolved schema, or null if not found
     */
    public static Schema<?> resolveSchemaRef(String schemaRef, String serviceName, OpenAPI spec) {
        if (schemaRef == null || spec == null || spec.getComponents() == null || spec.getComponents().getSchemas() == null) {
            return null;
        }
        
        String schemaName = schemaRef.replace("#/components/schemas/", "");
        Map<String, Schema> schemas = spec.getComponents().getSchemas();
        
        // First, try to find the schema directly
        if (schemas.containsKey(schemaName)) {
            return schemas.get(schemaName);
        }
        
        // If not found and starts with api_ or actuator_, try to resolve with service name
        if (schemaName.startsWith(API_PREFIX) && serviceName != null) {
            String resolvedName = serviceName + "_" + schemaName.substring(API_PREFIX.length());
            if (schemas.containsKey(resolvedName)) {
                logger.debug("Resolved schema ref {} to {} using service name {}", schemaName, resolvedName, serviceName);
                return schemas.get(resolvedName);
            }
        }
        
        if (schemaName.startsWith(ACTUATOR_PREFIX) && serviceName != null) {
            String resolvedName = serviceName + "_" + schemaName.substring(ACTUATOR_PREFIX.length());
            if (schemas.containsKey(resolvedName)) {
                logger.debug("Resolved schema ref {} to {} using service name {}", schemaName, resolvedName, serviceName);
                return schemas.get(resolvedName);
            }
        }
        
        // Try with current thread-local service name
        String currentService = currentServiceName.get();
        if (currentService != null && !currentService.equals(serviceName)) {
            if (schemaName.startsWith(API_PREFIX)) {
                String resolvedName = currentService + "_" + schemaName.substring(API_PREFIX.length());
                if (schemas.containsKey(resolvedName)) {
                    logger.debug("Resolved schema ref {} to {} using thread-local service name {}", schemaName, resolvedName, currentService);
                    return schemas.get(resolvedName);
                }
            }
            if (schemaName.startsWith(ACTUATOR_PREFIX)) {
                String resolvedName = currentService + "_" + schemaName.substring(ACTUATOR_PREFIX.length());
                if (schemas.containsKey(resolvedName)) {
                    logger.debug("Resolved schema ref {} to {} using thread-local service name {}", schemaName, resolvedName, currentService);
                    return schemas.get(resolvedName);
                }
            }
        }
        
        // Try to find any matching schema by suffix
        String suffix = schemaName.startsWith(API_PREFIX) ? schemaName.substring(API_PREFIX.length()) : 
                       (schemaName.startsWith(ACTUATOR_PREFIX) ? schemaName.substring(ACTUATOR_PREFIX.length()) : null);
        if (suffix != null) {
            for (String key : schemas.keySet()) {
                if (key.endsWith("_" + suffix)) {
                    logger.debug("Resolved schema ref {} to {} by suffix matching", schemaName, key);
                    return schemas.get(key);
                }
            }
        }
        
        logger.debug("Could not resolve schema ref: {}", schemaName);
        return null;
    }
    
    /**
     * Resolve a schema name (without the full path) to the actual schema.
     */
    public static Schema<?> resolveSchemaName(String schemaName, String serviceName, OpenAPI spec) {
        return resolveSchemaRef("#/components/schemas/" + schemaName, serviceName, spec);
    }
    
    /**
     * Get the resolved schema name for a reference.
     * 
     * @param schemaRef The original schema reference
     * @param serviceName The service name
     * @param spec The OpenAPI specification
     * @return The resolved schema name, or the original name if not resolvable
     */
    public static String getResolvedSchemaName(String schemaRef, String serviceName, OpenAPI spec) {
        if (schemaRef == null) {
            return null;
        }
        
        String schemaName = schemaRef.replace("#/components/schemas/", "");
        
        if (spec == null || spec.getComponents() == null || spec.getComponents().getSchemas() == null) {
            return schemaName;
        }
        
        Map<String, Schema> schemas = spec.getComponents().getSchemas();
        
        // First check if it exists directly
        if (schemas.containsKey(schemaName)) {
            return schemaName;
        }
        
        // Try to resolve with service name
        if (schemaName.startsWith(API_PREFIX) && serviceName != null) {
            String resolvedName = serviceName + "_" + schemaName.substring(API_PREFIX.length());
            if (schemas.containsKey(resolvedName)) {
                return resolvedName;
            }
        }
        
        if (schemaName.startsWith(ACTUATOR_PREFIX) && serviceName != null) {
            String resolvedName = serviceName + "_" + schemaName.substring(ACTUATOR_PREFIX.length());
            if (schemas.containsKey(resolvedName)) {
                return resolvedName;
            }
        }
        
        // Try with thread-local service name
        String currentService = currentServiceName.get();
        if (currentService != null) {
            if (schemaName.startsWith(API_PREFIX)) {
                String resolvedName = currentService + "_" + schemaName.substring(API_PREFIX.length());
                if (schemas.containsKey(resolvedName)) {
                    return resolvedName;
                }
            }
            if (schemaName.startsWith(ACTUATOR_PREFIX)) {
                String resolvedName = currentService + "_" + schemaName.substring(ACTUATOR_PREFIX.length());
                if (schemas.containsKey(resolvedName)) {
                    return resolvedName;
                }
            }
        }
        
        // Try suffix matching
        String suffix = schemaName.startsWith(API_PREFIX) ? schemaName.substring(API_PREFIX.length()) : 
                       (schemaName.startsWith(ACTUATOR_PREFIX) ? schemaName.substring(ACTUATOR_PREFIX.length()) : null);
        if (suffix != null) {
            for (String key : schemas.keySet()) {
                if (key.endsWith("_" + suffix)) {
                    return key;
                }
            }
        }
        
        return schemaName;
    }
}
