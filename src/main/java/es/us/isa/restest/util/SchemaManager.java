package es.us.isa.restest.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class SchemaManager {

    /*
        Swagger definitions may contain cycle references, i.e., A points to B, and
        B (eventually) points to A. In these cases, we cannot represent the whole
        object, e.g., A->B->A, so we represent A->B->{} (empty object without properties).
        To achieve this, we need to keep track of the references, which is the purpose
        of the currentRefPath.
     */
    private static String currentRefPath = "";

    private SchemaManager() {}

    public static Schema<?> generateFullyResolvedObjectSchema(Schema<?> schema, OpenAPI spec) {
        Schema<?> resolvedSchema = resolveSchemaAndUpdateRefPath(schema, spec);
        Schema copy = new Schema();

        prePopulateSchema(resolvedSchema, copy);

        Map<String, Schema> properties = null;
        if (resolvedSchema.getProperties() != null) {
            properties = new HashMap<>();

            for (Map.Entry<String, Schema> entry: resolvedSchema.getProperties().entrySet()) {
                Schema<?> entryResolvedSchema = generateFullyResolvedSchema(entry.getValue(), spec);
                properties.put(entry.getKey(), entryResolvedSchema);
            }
        }
        copy.setProperties(properties);

        return copy;
    }

    /**
     * No support for anyOf, oneOf. Possible support for allOf.
     * <br>
     * <br>
     * Given a schema, it generates a duplicate with all properties resolved (i.e.,
     * without "ref" attributes).
     */
    public static Schema<?> generateFullyResolvedSchema(Schema<?> schema, OpenAPI spec) {
        if (schema == null) {
            return null;
        }
        Schema<?> resolvedSchema = resolveSchemaAndUpdateRefPath(schema, spec);
        if (resolvedSchema == null) {
            return null;
        }
        Schema<?> fullyResolvedSchema;
        if ("array".equals(resolvedSchema.getType()))
            fullyResolvedSchema = generateFullyResolvedArraySchema((ArraySchema) resolvedSchema, spec);
        else
            fullyResolvedSchema = generateFullyResolvedObjectSchema(resolvedSchema, spec);

        if (schema.get$ref() != null) {
            String schemaSubRef = schema.get$ref().replace("#/components/schemas", "");
            currentRefPath = currentRefPath.replaceAll(schemaSubRef + "/.*", "");
            currentRefPath = currentRefPath.replaceAll(schemaSubRef + "$", "");
        }

        return fullyResolvedSchema;
    }

    public static ArraySchema generateFullyResolvedArraySchema(ArraySchema schema, OpenAPI spec) {
        if (schema == null) {
            return null;
        }
        ArraySchema resolvedSchema = (ArraySchema) resolveSchemaAndUpdateRefPath(schema, spec);
        if (resolvedSchema == null) {
            return null;
        }
        ArraySchema copy = new ArraySchema();

        prePopulateSchema(resolvedSchema, copy);

        if (resolvedSchema.getItems() != null) {
            Schema<?> itemsSchema = generateFullyResolvedSchema(resolvedSchema.getItems(), spec);
            copy.setItems(itemsSchema);
        }

        return copy;
    }

    public static void prePopulateSchema(Schema resolvedSchema, Schema<?> copy) {
        copy.set$ref(resolvedSchema.get$ref());
        copy.setAdditionalProperties(resolvedSchema.getAdditionalProperties());
        copy.setDefault(resolvedSchema.getDefault());
        copy.setDeprecated(resolvedSchema.getDeprecated());
        copy.setDescription(resolvedSchema.getDescription());
        copy.setDiscriminator(resolvedSchema.getDiscriminator());
        copy.setEnum(resolvedSchema.getEnum());
        copy.setExample(resolvedSchema.getExample());
        copy.setExclusiveMaximum(resolvedSchema.getExclusiveMaximum());
        copy.setExclusiveMinimum(resolvedSchema.getExclusiveMinimum());
        copy.setExtensions(resolvedSchema.getExtensions());
        copy.setExternalDocs(resolvedSchema.getExternalDocs());
        copy.setFormat(resolvedSchema.getFormat());
        copy.setMaximum(resolvedSchema.getMaximum());
        copy.setMaxItems(resolvedSchema.getMaxItems());
        copy.setMaxLength(resolvedSchema.getMaxLength());
        copy.setMaxProperties(resolvedSchema.getMaxProperties());
        copy.setMinimum(resolvedSchema.getMinimum());
        copy.setMinItems(resolvedSchema.getMinItems());
        copy.setMinLength(resolvedSchema.getMinLength());
        copy.setMinProperties(resolvedSchema.getMinProperties());
        copy.setMultipleOf(resolvedSchema.getMultipleOf());
        copy.setName(resolvedSchema.getName());
        copy.setNot(resolvedSchema.getNot());
        copy.setNullable(resolvedSchema.getNullable());
        copy.setPattern(resolvedSchema.getPattern());
        copy.setReadOnly(resolvedSchema.getReadOnly());
        copy.setRequired(resolvedSchema.getRequired());
        copy.setTitle(resolvedSchema.getTitle());
        copy.setType(resolvedSchema.getType());
        copy.setUniqueItems(resolvedSchema.getUniqueItems());
        copy.setWriteOnly(resolvedSchema.getWriteOnly());
        copy.setXml(resolvedSchema.getXml());
    }

    /**
     * Given a schema, it returns the same schema if it is already resolved (i.e.,
     * contains properties and has no ref attribute), or the resolved schema corresponding
     * to the ref attribute.
     */
    public static Schema<?> resolveSchema(Schema<?> schema, OpenAPI spec) {
        if (schema == null) {
            return null;
        }
        Schema resolvedSchema = schema;
        while (resolvedSchema != null && resolvedSchema.get$ref() != null) {
            String ref = resolvedSchema.get$ref();
            // Try to resolve using SchemaRefResolver which handles api_ prefix mapping
            Schema<?> resolved = SchemaRefResolver.resolveSchemaRef(ref, SchemaRefResolver.getCurrentServiceName(), spec);
            if (resolved != null) {
                resolvedSchema = resolved;
            } else if (spec.getComponents() != null && spec.getComponents().getSchemas() != null) {
                String refName = ref.replace("#/components/schemas/", "");
                resolvedSchema = spec.getComponents().getSchemas().get(refName);
            } else {
                break;
            }
            // Prevent infinite loop if resolved schema still has the same ref
            if (resolvedSchema != null && ref.equals(resolvedSchema.get$ref())) {
                break;
            }
        }
        return resolvedSchema;
    }

    private static Schema<?> resolveSchemaAndUpdateRefPath(Schema<?> schema, OpenAPI spec) {
        if (schema == null) {
            return null;
        }
        Schema resolvedSchema = schema;
        String schemaSubRef;
        while (resolvedSchema != null && resolvedSchema.get$ref() != null) {
            String originalRef = resolvedSchema.get$ref();
            // Get the resolved schema name using the service-aware resolver
            schemaSubRef = SchemaRefResolver.getResolvedSchemaName(originalRef, SchemaRefResolver.getCurrentServiceName(), spec);
            if (!Pattern.compile("/" + schemaSubRef + "/|/" + schemaSubRef + "$").matcher(currentRefPath).find()) {
                currentRefPath += "/" + schemaSubRef;
                // First try to resolve using SchemaRefResolver
                Schema<?> resolved = SchemaRefResolver.resolveSchemaRef(originalRef, SchemaRefResolver.getCurrentServiceName(), spec);
                if (resolved != null) {
                    resolvedSchema = resolved;
                } else if (spec.getComponents() != null && spec.getComponents().getSchemas() != null) {
                    resolvedSchema = spec.getComponents().getSchemas().get(schemaSubRef);
                } else {
                    break;
                }
            } else {
                resolvedSchema.set$ref(null);
                resolvedSchema.setType("object");
                resolvedSchema.setProperties(new HashMap<>());
            }
            // Prevent infinite loop
            if (resolvedSchema != null && originalRef.equals(resolvedSchema.get$ref())) {
                break;
            }
        }
        return resolvedSchema;
    }

    public static JsonNode createValueNode(Object value, ObjectMapper mapper) {
        JsonNode node = null;

        if (value == null) {
            node = mapper.getNodeFactory().nullNode();
        } else if (value instanceof Number) {
            Number n = (Number) value;

            if (n instanceof Integer || n instanceof Long) {
                node = mapper.getNodeFactory().numberNode(n.longValue());
            } else if (n instanceof BigInteger) {
                node = mapper.getNodeFactory().numberNode((BigInteger) n);
            } else if (n instanceof Double || n instanceof Float) {
                node = mapper.getNodeFactory().numberNode(n.doubleValue());
            } else if (n instanceof BigDecimal) {
                node = mapper.getNodeFactory().numberNode((BigDecimal) n);
            }
        } else if (value instanceof Boolean) {
            node = mapper.getNodeFactory().booleanNode((Boolean) value);
        } else if (value instanceof OffsetDateTime) {
            node = mapper.getNodeFactory().textNode(((OffsetDateTime) value).toString());
        } else if (value instanceof java.util.Date) {
            // Handle java.util.Date by converting to ISO-8601 string format
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
            node = mapper.getNodeFactory().textNode(sdf.format((java.util.Date) value));
        } else if (value instanceof String) {
            node = mapper.getNodeFactory().textNode((String) value);
        } else {
            // Fallback: convert any other type to string
            node = mapper.getNodeFactory().textNode(value.toString());
        }

        return node;
    }
}
