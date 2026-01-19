package es.us.isa.restest.mutation.rules;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static es.us.isa.restest.util.SchemaManager.resolveSchema;

public abstract class SingleRule {

    protected final Random random = new SecureRandom();

    protected SingleRule() {}

    public void apply(Schema<?> schema, boolean internalNode, OpenAPI spec) {
        if ("array".equals(schema.getType())) {
            apply(((ArraySchema)schema).getItems(), internalNode, spec);
        } else if (schema.getProperties() != null) {
            List<Schema> objectNodes = getAllObjectNodes(schema, internalNode, spec);
            Schema s = objectNodes.get(random.nextInt(objectNodes.size()));

            applyNodeFuzzingRule(s, spec);
        }
    }

    private List<Schema> getAllObjectNodes(Schema<?> schema, boolean internalNode, OpenAPI spec) {
        List<Schema> objectNodes = new ArrayList<>();

        objectNodes.add(schema);

        for(Map.Entry<String, Schema> entry : schema.getProperties().entrySet()) {
            Schema<?> entrySchema = entry.getValue();
            // Skip null schema entries
            if (entrySchema == null) {
                continue;
            }
            if ("array".equals(entrySchema.getType())) {
                apply(((ArraySchema) entrySchema).getItems(), internalNode, spec);
            } else if ("object".equals(entrySchema.getType())) {
                objectNodes.addAll(getAllObjectNodes(entrySchema, internalNode, spec));
            } else if (!internalNode) {
                objectNodes.add(entrySchema);
            }
        }

        return objectNodes;
    }

    protected abstract void applyNodeFuzzingRule(Schema<?> schema, OpenAPI spec);
}
