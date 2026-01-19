package es.us.isa.restest.mutation.rules;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;

import java.security.SecureRandom;
import java.util.*;

public abstract class PathRule {

    protected final Random random = new SecureRandom();

    protected PathRule() {}

    public void apply(Schema<?> schema, OpenAPI spec) {
        if ("array".equals(schema.getType())) {
            apply(((ArraySchema) schema).getItems(), spec);
        } else if (schema.getProperties() != null) {
            List<Map.Entry<String, Schema>> objectNodes = new ArrayList<>();

            for (Map.Entry<String, Schema> entry : schema.getProperties().entrySet()) {
                Schema<?> entrySchema = entry.getValue();
                // Skip null schema entries
                if (entrySchema == null) {
                    continue;
                }
                if ("array".equals(entrySchema.getType())) {
                    apply(((ArraySchema) entrySchema).getItems(), spec);
                } else if ("object".equals(entrySchema.getType())) {
                    objectNodes.add(entry);
                }
            }

            String objectChild = "";
            if (!objectNodes.isEmpty()) {
                Map.Entry<String, Schema> entry = objectNodes.get(random.nextInt(objectNodes.size()));
                objectChild = entry.getKey();
                apply(entry.getValue(), spec);
            }

            applyNodeFuzzingRule(schema, objectChild);
        }
    }

    protected abstract void applyNodeFuzzingRule(Schema<?> schema, String objectChild);
}
