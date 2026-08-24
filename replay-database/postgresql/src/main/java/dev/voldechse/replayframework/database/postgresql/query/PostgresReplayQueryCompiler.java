package dev.voldechse.replayframework.database.postgresql.query;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.inject.Inject;
import dev.voldechse.replayframework.api.metadata.QueryCapabilities;
import dev.voldechse.replayframework.api.metadata.ReplayMetadataKey;
import dev.voldechse.replayframework.api.query.MetadataPredicate;
import dev.voldechse.replayframework.api.query.ReplayQuery;
import dev.voldechse.replayframework.api.recording.RecordingStatus;
import dev.voldechse.replayframework.core.metadata.MetadataRegistry;
import dev.voldechse.replayframework.core.port.ReplayRepository;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.key.Key;

/**
 * Compiles the public replay query AST into parameterized PostgreSQL expressions.
 *
 * <p>Only compiler-owned SQL fragments are concatenated. Keys, JSON values, cursor values and
 * fixed-field values are always represented as bound parameters.</p>
 */
public final class PostgresReplayQueryCompiler {
    private static final String DEFAULT_ORDER = "r.created_at DESC, r.id ASC";
    private static final int CURSOR_VERSION = 1;

    private final MetadataRegistry registry;
    private final Gson gson;

    /** Creates a compiler using the shared runtime registry and Gson instance. */
    @Inject
    public PostgresReplayQueryCompiler(MetadataRegistry registry, Gson gson) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    /** Compiles filters, sorting and an optional continuation cursor. */
    public CompiledQuery compile(ReplayQuery query) {
        Objects.requireNonNull(query, "query");
        StringBuilder where = new StringBuilder();
        List<Parameter> parameters = new ArrayList<>();
        appendFixedFilters(query, where, parameters);
        appendMetadataFilters(query, where, parameters);

        List<SortTerm> sortTerms = sortTerms(query, parameters);
        query.cursor().ifPresent(cursor -> appendCursor(
                query, cursor, sortTerms, where, parameters));

        String orderBy = sortTerms.isEmpty()
                ? DEFAULT_ORDER
                : sortTerms.stream().map(SortTerm::sql).reduce((left, right) -> left + ", " + right)
                        .orElseThrow();
        return new CompiledQuery(where.toString(), orderBy, parameters, query.limit());
    }

    /** Encodes the last row of a page as a cursor compatible with the query sort. */
    public String encodeCursor(ReplayQuery query, ReplayRepository.ReplayRow row) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(row, "row");
        JsonObject payload = new JsonObject();
        payload.addProperty("version", CURSOR_VERSION);
        payload.add("sort", sortSignature(query));
        JsonArray values = new JsonArray();
        if (query.sort().isEmpty()) {
            values.add(cursorComponent(gson.toJsonTree(row.createdAt().toString())));
        } else {
            for (ReplayQuery.SortSpec specification : query.sort()) {
                values.add(cursorComponent(cursorValue(specification, row)));
            }
        }
        payload.add("values", values);
        payload.addProperty("id", row.replayId().value().toString());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                gson.toJson(payload).getBytes(StandardCharsets.UTF_8));
    }

    private void appendFixedFilters(
            ReplayQuery query,
            StringBuilder where,
            List<Parameter> parameters) {
        query.status().ifPresent(status -> {
            where.append(" AND r.status = cast(:status as replay_status)");
            parameters.add(new Parameter("status", status.name(), ParameterKind.TEXT));
        });
        query.adapterId().ifPresent(adapterId -> {
            where.append(" AND r.adapter_id = :adapter_id");
            parameters.add(new Parameter("adapter_id", adapterId, ParameterKind.TEXT));
        });
        query.createdFromInclusive().ifPresent(createdFrom -> {
            where.append(" AND r.created_at >= :created_from");
            parameters.add(new Parameter("created_from", createdFrom, ParameterKind.INSTANT));
        });
        query.createdToExclusive().ifPresent(createdTo -> {
            where.append(" AND r.created_at < :created_to");
            parameters.add(new Parameter("created_to", createdTo, ParameterKind.INSTANT));
        });
        query.titleEquals().ifPresent(title -> {
            where.append(" AND r.title = :title");
            parameters.add(new Parameter("title", title, ParameterKind.TEXT));
        });
    }

    private void appendMetadataFilters(
            ReplayQuery query,
            StringBuilder where,
            List<Parameter> parameters) {
        int index = 0;
        for (ReplayQuery.MetadataCriterion criterion : query.metadataCriteria()) {
            MetadataRegistry.RegisteredKey<?> registered = registry.require(criterion.key());
            QueryCapabilities.Operation operation = criterion.predicate().operation();
            if (!registered.definition().queryCapabilities().supports(operation)) {
                throw new IllegalArgumentException(
                        "metadata key does not support " + operation + ": "
                                + criterion.key().key().asString());
            }
            String keyParameter = "metadata_key_" + index;
            MetadataPredicate<?> predicate = criterion.predicate();
            if (predicate instanceof MetadataPredicate.Exists<?>) {
                where.append(" AND jsonb_exists(r.metadata, :").append(keyParameter).append(')');
                parameters.add(new Parameter(
                        keyParameter, criterion.key().key().asString(), ParameterKind.TEXT));
            } else if (predicate instanceof MetadataPredicate.EqualTo<?> equalTo) {
                appendContainment(
                        where, parameters, "metadata_value_" + index,
                        criterion.key().key(), registered, equalTo.value());
            } else if (predicate instanceof MetadataPredicate.OneOf<?> oneOf) {
                where.append(" AND (");
                int valueIndex = 0;
                for (Object value : oneOf.values()) {
                    if (valueIndex > 0) {
                        where.append(" OR ");
                    }
                    appendContainment(
                            where, parameters, "metadata_value_" + index + '_' + valueIndex,
                            criterion.key().key(), registered, value);
                    valueIndex++;
                }
                where.append(')');
            } else if (predicate instanceof MetadataPredicate.Contains<?> contains) {
                appendContainment(
                        where, parameters, "metadata_value_" + index,
                        criterion.key().key(), registered, contains.value());
            } else if (predicate instanceof MetadataPredicate.Between<?> between) {
                ScalarType scalarType = scalarType(registered.definition());
                String expression = scalarExpression(keyParameter, scalarType);
                String lowerParameter = "metadata_lower_" + index;
                String upperParameter = "metadata_upper_" + index;
                where.append(" AND ").append(expression)
                        .append(" >= :").append(lowerParameter)
                        .append(" AND ").append(expression)
                        .append(" <= :").append(upperParameter);
                parameters.add(new Parameter(
                        keyParameter, criterion.key().key().asString(), ParameterKind.TEXT));
                parameters.add(new Parameter(
                        lowerParameter, scalarValue(registered, between.lowerInclusive()),
                        scalarType.parameterKind()));
                parameters.add(new Parameter(
                        upperParameter, scalarValue(registered, between.upperInclusive()),
                        scalarType.parameterKind()));
            } else {
                throw new IllegalArgumentException(
                        "unsupported metadata predicate: " + predicate.getClass().getName());
            }
            index++;
        }
    }

    private void appendContainment(
            StringBuilder where,
            List<Parameter> parameters,
            String valueParameter,
            Key key,
            MetadataRegistry.RegisteredKey<?> registered,
            Object value) {
        where.append("r.metadata @> cast(:").append(valueParameter).append(" as jsonb)");
        JsonObject object = new JsonObject();
        object.add(key.asString(), encodeValue(registered, value));
        parameters.add(new Parameter(valueParameter, object, ParameterKind.JSONB));
    }

    private List<SortTerm> sortTerms(ReplayQuery query, List<Parameter> parameters) {
        if (query.sort().isEmpty()) {
            return List.of();
        }
        List<SortTerm> terms = new ArrayList<>();
        int index = 0;
        for (ReplayQuery.SortSpec specification : query.sort()) {
            String direction = direction(specification);
            if (specification instanceof ReplayQuery.FixedSort fixed) {
                terms.add(new SortTerm(
                        fixedField(fixed.field()) + ' ' + direction,
                        fixedField(fixed.field()),
                        null,
                        fixed.field(),
                        fixed.direction()));
            } else if (specification instanceof ReplayQuery.MetadataSort metadataSort) {
                MetadataRegistry.RegisteredKey<?> registered = registry.require(metadataSort.key());
                requireCapability(
                        registered.definition(), QueryCapabilities.Operation.ORDERING,
                        metadataSort.key().key());
                ScalarType scalarType = scalarType(registered.definition());
                String keyParameter = "sort_key_" + index;
                parameters.add(new Parameter(
                        keyParameter, metadataSort.key().key().asString(), ParameterKind.TEXT));
                String expression = scalarExpression(keyParameter, scalarType);
                terms.add(new SortTerm(
                        expression + ' ' + direction + " NULLS LAST",
                        expression,
                        metadataSort.key(),
                        null,
                        metadataSort.direction()));
            } else {
                throw new IllegalArgumentException(
                        "unsupported replay sort: " + specification.getClass().getName());
            }
            index++;
        }
        terms.add(new SortTerm(
                "r.id ASC", "r.id", null, null, ReplayQuery.SortDirection.ASCENDING));
        return List.copyOf(terms);
    }

    private void appendCursor(
            ReplayQuery query,
            String encoded,
            List<SortTerm> sortTerms,
            StringBuilder where,
            List<Parameter> parameters) {
        Cursor cursor = decodeCursor(query, encoded);
        if (query.sort().isEmpty()) {
            where.append(" AND (r.created_at < :cursor_0"
                    + " OR (r.created_at = :cursor_0 AND r.id > :cursor_id))");
            parameters.add(new Parameter(
                    "cursor_0",
                    Instant.parse(cursorScalarValue(cursor.values().get(0)).getAsString()),
                    ParameterKind.INSTANT));
            parameters.add(new Parameter("cursor_id", cursor.id(), ParameterKind.UUID));
            return;
        }

        List<String> branches = new ArrayList<>();
        for (int index = 0; index < query.sort().size(); index++) {
            StringBuilder branch = new StringBuilder("(");
            for (int equalIndex = 0; equalIndex < index; equalIndex++) {
                appendCursorEquality(
                        branch, sortTerms.get(equalIndex), cursor.values().get(equalIndex), equalIndex);
                branch.append(" AND ");
            }
            SortTerm term = sortTerms.get(index);
            JsonElement component = cursor.values().get(index);
            if (isMissing(component)) {
                // With NULLS LAST, no value follows a missing value at this position.
                continue;
            }
            branch.append(term.expression())
                    .append(term.direction() == ReplayQuery.SortDirection.ASCENDING ? " > " : " < ")
                    .append(cursorParameterReference(term, index));
            if (term.metadataKey() != null) {
                // Metadata sorts use NULLS LAST, so missing values follow every non-null value.
                branch.append(" OR ").append(term.expression()).append(" IS NULL");
            }
            branch.append(')');
            parameters.add(cursorParameter(term, component, index));
            branches.add(branch.toString());
        }
        StringBuilder tieBranch = new StringBuilder("(");
        for (int index = 0; index < query.sort().size(); index++) {
            appendCursorEquality(
                    tieBranch, sortTerms.get(index), cursor.values().get(index), index);
            tieBranch.append(" AND ");
        }
        tieBranch.append("r.id > :cursor_id)");
        branches.add(tieBranch.toString());
        where.append(" AND ").append(String.join(" OR ", branches));
        parameters.add(new Parameter("cursor_id", cursor.id(), ParameterKind.UUID));
    }

    private Parameter cursorParameter(SortTerm term, JsonElement value, int index) {
        JsonElement scalar = cursorScalarValue(value);
        if (term.metadataKey() != null) {
            MetadataRegistry.RegisteredKey<?> registered = registry.require(term.metadataKey());
            ScalarType scalarType = scalarType(registered.definition());
            return new Parameter(
                    "cursor_" + index,
                    scalarValueFromJson(registered, scalar),
                    scalarType.parameterKind());
        }
        return new Parameter(
                "cursor_" + index,
                fixedCursorValue(term.fixedField(), scalar),
                fixedParameterKind(term.fixedField()));
    }

    private static void appendCursorEquality(
            StringBuilder branch,
            SortTerm term,
            JsonElement component,
            int index) {
        branch.append(term.expression());
        if (isMissing(component)) {
            branch.append(" IS NULL");
        } else {
            branch.append(" = ").append(cursorParameterReference(term, index));
        }
    }

    private static String cursorParameterReference(SortTerm term, int index) {
        String parameter = ":cursor_" + index;
        return term.fixedField() == ReplayQuery.SortField.STATUS
                ? "cast(" + parameter + " as replay_status)"
                : parameter;
    }

    private Cursor decodeCursor(ReplayQuery query, String encoded) {
        try {
            String json = new String(
                    Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            JsonObject payload = gson.fromJson(json, JsonObject.class);
            if (payload == null || payload.get("version").getAsInt() != CURSOR_VERSION
                    || !sortSignature(query).equals(payload.get("sort"))
                    || !payload.has("values") || !payload.has("id")) {
                throw new IllegalArgumentException("cursor does not match replay query");
            }
            JsonArray values = payload.getAsJsonArray("values");
            int expectedValues = query.sort().isEmpty() ? 1 : query.sort().size();
            if (values.size() != expectedValues) {
                throw new IllegalArgumentException("cursor value count does not match replay query");
            }
            List<JsonElement> components = new ArrayList<>(values.size());
            for (JsonElement value : values) {
                validateCursorComponent(value);
                components.add(value.deepCopy());
            }
            return new Cursor(
                    components, UUID.fromString(payload.get("id").getAsString()));
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException
                    && exception.getMessage() != null
                    && exception.getMessage().startsWith("cursor")) {
                throw exception;
            }
            throw new IllegalArgumentException("invalid replay page cursor", exception);
        }
    }

    private JsonElement sortSignature(ReplayQuery query) {
        JsonArray signature = new JsonArray();
        if (query.sort().isEmpty()) {
            signature.add("CREATED_AT:DESCENDING");
            return signature;
        }
        for (ReplayQuery.SortSpec specification : query.sort()) {
            if (specification instanceof ReplayQuery.FixedSort fixed) {
                signature.add(fixed.field().name() + ':' + fixed.direction().name());
            } else if (specification instanceof ReplayQuery.MetadataSort metadataSort) {
                signature.add("METADATA:" + metadataSort.key().key().asString()
                        + ':' + metadataSort.direction().name());
            }
        }
        return signature;
    }

    private JsonElement cursorValue(
            ReplayQuery.SortSpec specification,
            ReplayRepository.ReplayRow row) {
        if (specification instanceof ReplayQuery.FixedSort fixed) {
            return switch (fixed.field()) {
                case CREATED_AT -> gson.toJsonTree(row.createdAt().toString());
                case TITLE -> gson.toJsonTree(row.title());
                case DURATION -> gson.toJsonTree(row.durationNanos());
                case STATUS -> gson.toJsonTree(row.status().name());
                case ADAPTER_ID -> gson.toJsonTree(row.adapterId());
                case TOTAL_BYTES -> gson.toJsonTree(row.totalBytes());
            };
        }
        ReplayQuery.MetadataSort metadataSort = (ReplayQuery.MetadataSort) specification;
        JsonElement value = row.metadata().get(metadataSort.key().key().asString());
        return value == null ? com.google.gson.JsonNull.INSTANCE : value.deepCopy();
    }

    private static JsonObject cursorComponent(JsonElement value) {
        JsonObject component = new JsonObject();
        boolean missing = value == null || value.isJsonNull();
        component.addProperty("missing", missing);
        if (!missing) {
            component.add("value", value.deepCopy());
        }
        return component;
    }

    private static void validateCursorComponent(JsonElement component) {
        if (!component.isJsonObject()) {
            throw new IllegalArgumentException("invalid replay page cursor component");
        }
        JsonObject object = component.getAsJsonObject();
        if (object.get("missing") == null
                || !object.get("missing").isJsonPrimitive()
                || !object.get("missing").getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("invalid replay page cursor component");
        }
        boolean missing = object.get("missing").getAsBoolean();
        if (missing != !object.has("value")) {
            throw new IllegalArgumentException("invalid replay page cursor component");
        }
    }

    private static boolean isMissing(JsonElement component) {
        validateCursorComponent(component);
        return component.getAsJsonObject().get("missing").getAsBoolean();
    }

    private static JsonElement cursorScalarValue(JsonElement component) {
        validateCursorComponent(component);
        JsonObject object = component.getAsJsonObject();
        if (object.get("missing").getAsBoolean()) {
            throw new IllegalArgumentException("missing cursor value cannot be bound");
        }
        return object.get("value");
    }

    private Object scalarValue(
            MetadataRegistry.RegisteredKey<?> registered,
            Object value) {
        return scalarValueFromJson(registered, encodeValue(registered, value));
    }

    private Object scalarValueFromJson(
            MetadataRegistry.RegisteredKey<?> registered,
            JsonElement value) {
        if (value.isJsonNull()) {
            throw new IllegalArgumentException("ordered metadata value must not be JSON null");
        }
        return switch (scalarType(registered.definition())) {
            case TEXT -> value.getAsString();
            case UUID -> UUID.fromString(value.getAsString());
            case INSTANT -> Instant.parse(value.getAsString());
            case BOOLEAN -> value.getAsBoolean();
            case NUMERIC -> new BigDecimal(value.getAsString());
        };
    }

    private static Object fixedCursorValue(ReplayQuery.SortField field, JsonElement value) {
        return switch (field) {
            case CREATED_AT -> Instant.parse(value.getAsString());
            case TITLE, ADAPTER_ID -> value.getAsString();
            case DURATION, TOTAL_BYTES -> value.getAsLong();
            case STATUS -> RecordingStatus.valueOf(value.getAsString()).name();
        };
    }

    private static ParameterKind fixedParameterKind(ReplayQuery.SortField field) {
        return switch (field) {
            case CREATED_AT -> ParameterKind.INSTANT;
            case TITLE, STATUS, ADAPTER_ID -> ParameterKind.TEXT;
            case DURATION, TOTAL_BYTES -> ParameterKind.LONG;
        };
    }

    private static String fixedField(ReplayQuery.SortField field) {
        return switch (field) {
            case CREATED_AT -> "r.created_at";
            case TITLE -> "r.title";
            case DURATION -> "r.duration_nanos";
            case STATUS -> "r.status";
            case ADAPTER_ID -> "r.adapter_id";
            case TOTAL_BYTES -> "r.total_bytes";
        };
    }

    private static String direction(ReplayQuery.SortSpec specification) {
        ReplayQuery.SortDirection direction = specification instanceof ReplayQuery.FixedSort fixed
                ? fixed.direction()
                : ((ReplayQuery.MetadataSort) specification).direction();
        return direction == ReplayQuery.SortDirection.ASCENDING ? "ASC" : "DESC";
    }

    private static String scalarExpression(String keyParameter, ScalarType scalarType) {
        return "cast(jsonb_extract_path_text(r.metadata, :" + keyParameter + ") as "
                + scalarType.sqlType() + ')';
    }

    private static ScalarType scalarType(ReplayMetadataKey<?> key) {
        Class<?> rawType = key.type().getRawType();
        if (rawType == String.class || rawType.isEnum()) {
            return ScalarType.TEXT;
        }
        if (rawType == Boolean.class || rawType == boolean.class) {
            return ScalarType.BOOLEAN;
        }
        if (rawType == UUID.class) {
            return ScalarType.UUID;
        }
        if (rawType == Instant.class) {
            return ScalarType.INSTANT;
        }
        if (rawType == Byte.class || rawType == byte.class
                || rawType == Short.class || rawType == short.class
                || rawType == Integer.class || rawType == int.class
                || rawType == Long.class || rawType == long.class
                || rawType == Float.class || rawType == float.class
                || rawType == Double.class || rawType == double.class
                || rawType == BigInteger.class || rawType == BigDecimal.class) {
            return ScalarType.NUMERIC;
        }
        throw new IllegalArgumentException(
                "metadata key type cannot be ordered in PostgreSQL: " + rawType.getName());
    }

    private static void requireCapability(
            ReplayMetadataKey<?> key,
            QueryCapabilities.Operation operation,
            Key canonicalKey) {
        if (!key.queryCapabilities().supports(operation)) {
            throw new IllegalArgumentException(
                    "metadata key does not support " + operation + ": " + canonicalKey.asString());
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static JsonElement encodeValue(
            MetadataRegistry.RegisteredKey<?> registered,
            Object value) {
        ReplayMetadataKey<?> key = registered.definition();
        Objects.requireNonNull(value, "metadata value");
        if (!key.type().getRawType().isInstance(value)) {
            throw new IllegalArgumentException(
                    "metadata value does not match key type: " + key.key().asString());
        }
        JsonElement element = ((TypeAdapter) key.codec()).toJsonTree(value);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException(
                    "metadata codec returned JSON null: " + key.key().asString());
        }
        return element.deepCopy();
    }

    private record SortTerm(
            String sql,
            String expression,
            ReplayMetadataKey<?> metadataKey,
            ReplayQuery.SortField fixedField,
            ReplayQuery.SortDirection direction) {
    }

    private record Cursor(List<JsonElement> values, UUID id) {
    }

    private enum ScalarType {
        TEXT("text", ParameterKind.TEXT),
        BOOLEAN("boolean", ParameterKind.BOOLEAN),
        NUMERIC("numeric", ParameterKind.NUMERIC),
        UUID("uuid", ParameterKind.UUID),
        INSTANT("timestamptz", ParameterKind.INSTANT);

        private final String sqlType;
        private final ParameterKind parameterKind;

        ScalarType(String sqlType, ParameterKind parameterKind) {
            this.sqlType = sqlType;
            this.parameterKind = parameterKind;
        }

        String sqlType() {
            return sqlType;
        }

        ParameterKind parameterKind() {
            return parameterKind;
        }
    }

    /** Immutable SQL and parameter result consumed by the repository. */
    public record CompiledQuery(
            String whereClause,
            String orderByClause,
            List<Parameter> parameters,
            int limit) {
        public CompiledQuery {
            Objects.requireNonNull(whereClause, "whereClause");
            Objects.requireNonNull(orderByClause, "orderByClause");
            parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive");
            }
        }
    }

    /** One named native-query parameter. */
    public record Parameter(String name, Object value, ParameterKind kind) {
        public Parameter {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** JDBC binding family used by the repository when binding compiled parameters. */
    public enum ParameterKind {
        TEXT,
        JSONB,
        LONG,
        BOOLEAN,
        UUID,
        INSTANT,
        NUMERIC
    }
}
