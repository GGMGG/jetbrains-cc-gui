package com.github.claudecodegui.mcp.marketplace;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps MCP registry JSON envelopes into CoDriver marketplace entries.
 */
final class McpRegistryEntryMapper {

    private McpRegistryEntryMapper() {
    }

    static McpMarketplaceEntry fromRegistryObject(JsonObject envelope, McpMarketplaceSource source) {
        // Registry v0.1 wraps each entry as { "server": { ... }, "_meta": { ... } }.
        // Older/flat payloads keep the fields at the top level, so fall back to the envelope itself.
        JsonObject data = McpMarketplaceJson.getObject(envelope, "server");
        if (data == null) {
            data = envelope;
        }
        JsonObject versionDetail = McpMarketplaceJson.getObject(data, "version_detail");
        String name = firstValue(
            McpMarketplaceJson.getString(data, "name", "id", "server_name"),
            McpMarketplaceJson.getString(versionDetail, "name", "id", "server_name")
        );
        String displayName = firstValue(
            McpMarketplaceJson.getString(data, "title", "display_name", "displayName"),
            McpMarketplaceJson.getString(versionDetail, "title", "display_name", "displayName"),
            shortName(name)
        );
        String description = firstValue(
            McpMarketplaceJson.getString(data, "description"),
            McpMarketplaceJson.getString(versionDetail, "description")
        );
        String version = firstValue(
            McpMarketplaceJson.getString(data, "version"),
            McpMarketplaceJson.getString(versionDetail, "version")
        );
        String status = firstValue(McpMarketplaceJson.getString(data, "status"), "active");
        String repositoryUrl = getRepositoryUrl(data);
        // The "official" marker lives in the outer envelope's _meta, but tolerate it on the server too.
        boolean official = isOfficial(envelope) || isOfficial(data);

        McpMarketplaceEntry.Builder builder = McpMarketplaceEntry.builder()
            .id(source.getId() + ":" + name)
            .name(name)
            .displayName(displayName)
            .description(description)
            .status(status)
            .source(source)
            .homepage(repositoryUrl)
            .repositoryUrl(repositoryUrl)
            .docsUrl(repositoryUrl)
            .official(official)
            .addTag(source.getName());

        if (version != null) {
            builder.addTag(version);
        }
        if (official) {
            builder.addTag("official");
        }

        addInstallOptions(builder, data, versionDetail, name, source);
        return builder.build();
    }

    static McpMarketplaceEntry fromGitHubRepo(JsonObject repo, McpMarketplaceSource source) {
        boolean fork = McpMarketplaceJson.getBoolean(repo, "fork", false);
        if (fork) {
            return null;
        }

        String repoName = McpMarketplaceJson.getString(repo, "name");
        String description = McpMarketplaceJson.getString(repo, "description");
        String repoUrl = McpMarketplaceJson.getString(repo, "html_url");
        String language = McpMarketplaceJson.getString(repo, "language");
        int stars = McpMarketplaceJson.getInt(repo, "stargazers_count", 0);
        boolean archived = McpMarketplaceJson.getBoolean(repo, "archived", false);

        StringBuilder desc = new StringBuilder();
        if (description != null && !description.trim().isEmpty()) {
            desc.append(description);
        }
        if (language != null && !language.trim().isEmpty()) {
            appendMetadata(desc, language);
        }
        if (stars > 0) {
            appendMetadata(desc, "★ " + stars);
        }

        return McpMarketplaceEntry.builder()
            .id(source.getId() + ":" + repoName)
            .name("io.github.modelcontextprotocol/" + repoName)
            .displayName(repoName)
            .description(desc.toString())
            .status(archived ? "archived" : "active")
            .source(source)
            .homepage(repoUrl)
            .repositoryUrl(repoUrl)
            .docsUrl(repoUrl)
            .official(true)
            .addTag("github")
            .addTag(language)
            .build();
    }

    private static void addInstallOptions(
        McpMarketplaceEntry.Builder builder,
        JsonObject envelope,
        JsonObject versionDetail,
        String serverName,
        McpMarketplaceSource source
    ) {
        List<VariableDefinition> variables = new ArrayList<>();
        variables.addAll(parseVariables(envelope));
        variables.addAll(parseVariables(versionDetail));

        List<HeaderDefinition> headers = new ArrayList<>();
        headers.addAll(parseHeaders(envelope));
        headers.addAll(parseHeaders(versionDetail));

        addRemoteInstallOptions(builder, envelope, variables, headers, source);
        addRemoteInstallOptions(builder, versionDetail, variables, headers, source);
        addPackageInstallOptions(builder, envelope, serverName, variables, source);
        addPackageInstallOptions(builder, versionDetail, serverName, variables, source);
    }

    private static void addRemoteInstallOptions(
        McpMarketplaceEntry.Builder builder,
        JsonObject object,
        List<VariableDefinition> variables,
        List<HeaderDefinition> headers,
        McpMarketplaceSource source
    ) {
        JsonArray remotes = McpMarketplaceJson.getArray(object, "remotes");
        if (remotes == null) {
            return;
        }
        for (JsonElement element : remotes) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject remote = element.getAsJsonObject();
            String url = McpMarketplaceJson.getString(remote, "url");
            if (url == null || url.trim().isEmpty()) {
                continue;
            }
            String transportType = firstValue(
                McpMarketplaceJson.getString(remote, "transport_type", "transportType", "type"),
                "http"
            );
            List<VariableDefinition> remoteVariables = new ArrayList<>(variables);
            remoteVariables.addAll(parseVariables(remote));
            List<HeaderDefinition> remoteHeaders = new ArrayList<>(headers);
            remoteHeaders.addAll(parseHeaders(remote));
            builder.addInstallOption(new McpInstallOption(
                transportType.toUpperCase(Locale.ROOT) + " remote",
                normalizeRemoteType(transportType),
                null,
                null,
                url,
                toEnvPlaceholders(remoteVariables),
                toHeaderPlaceholders(remoteHeaders),
                source.getName(),
                "remote"
            ));
        }
    }

    private static void addPackageInstallOptions(
        McpMarketplaceEntry.Builder builder,
        JsonObject object,
        String serverName,
        List<VariableDefinition> variables,
        McpMarketplaceSource source
    ) {
        JsonArray packages = McpMarketplaceJson.getArray(object, "packages");
        if (packages == null) {
            return;
        }
        for (JsonElement element : packages) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject packageObject = element.getAsJsonObject();
            PackageDefinition packageDefinition = PackageDefinition.from(packageObject, serverName);
            McpInstallOption installOption = createPackageInstallOption(packageDefinition, variables, source);
            if (installOption != null) {
                builder.addInstallOption(installOption);
            }
        }
    }

    private static McpInstallOption createPackageInstallOption(
        PackageDefinition packageDefinition,
        List<VariableDefinition> variables,
        McpMarketplaceSource source
    ) {
        if (packageDefinition.name == null || packageDefinition.name.trim().isEmpty()) {
            return null;
        }
        String registryType = normalizeRegistryType(packageDefinition.registryType);
        if ("npm".equals(registryType)) {
            return new McpInstallOption(
                "NPX package",
                "stdio",
                "npx",
                Arrays.asList("-y", packageDefinition.installName()),
                null,
                toEnvPlaceholders(variables),
                null,
                source.getName(),
                "local-command"
            );
        }
        if ("pypi".equals(registryType) || "python".equals(registryType) || "uv".equals(registryType)) {
            return new McpInstallOption(
                "UVX package",
                "stdio",
                "uvx",
                Arrays.asList(packageDefinition.installName()),
                null,
                toEnvPlaceholders(variables),
                null,
                source.getName(),
                "local-command"
            );
        }
        if ("docker".equals(registryType) || "oci".equals(registryType)) {
            return new McpInstallOption(
                "Docker image",
                "stdio",
                "docker",
                Arrays.asList("run", "-i", "--rm", packageDefinition.name, "stdio"),
                null,
                toEnvPlaceholders(variables),
                null,
                source.getName(),
                "container-command"
            );
        }
        return null;
    }

    private static List<VariableDefinition> parseVariables(JsonObject object) {
        List<VariableDefinition> result = new ArrayList<>();
        JsonArray variables = McpMarketplaceJson.getArray(object, "variables");
        if (variables == null) {
            return result;
        }
        for (JsonElement element : variables) {
            if (element.isJsonObject()) {
                VariableDefinition definition = VariableDefinition.from(element.getAsJsonObject());
                if (definition.name != null) {
                    result.add(definition);
                }
            }
        }
        return result;
    }

    private static List<HeaderDefinition> parseHeaders(JsonObject object) {
        List<HeaderDefinition> result = new ArrayList<>();
        JsonArray headers = McpMarketplaceJson.getArray(object, "headers");
        if (headers == null) {
            return result;
        }
        for (JsonElement element : headers) {
            if (element.isJsonObject()) {
                HeaderDefinition definition = HeaderDefinition.from(element.getAsJsonObject());
                if (definition.name != null) {
                    result.add(definition);
                }
            }
        }
        return result;
    }

    private static Map<String, String> toEnvPlaceholders(List<VariableDefinition> variables) {
        Map<String, String> values = new LinkedHashMap<>();
        for (VariableDefinition variable : variables) {
            if (variable.name == null || variable.name.trim().isEmpty()) {
                continue;
            }
            String value = variable.defaultValue != null ? variable.defaultValue : "{" + variable.name.toLowerCase(Locale.ROOT) + "}";
            values.put(variable.name, value);
        }
        return values;
    }

    private static Map<String, String> toHeaderPlaceholders(List<HeaderDefinition> headers) {
        Map<String, String> values = new LinkedHashMap<>();
        for (HeaderDefinition header : headers) {
            if (header.name == null || header.name.trim().isEmpty()) {
                continue;
            }
            values.put(header.name, "{" + header.name.toLowerCase(Locale.ROOT).replace('-', '_') + "}");
        }
        return values;
    }

    private static boolean isOfficial(JsonObject envelope) {
        JsonObject meta = McpMarketplaceJson.getObject(envelope, "_meta");
        JsonObject official = McpMarketplaceJson.getObject(meta, "io.modelcontextprotocol.registry/official");
        return official != null;
    }

    private static String getRepositoryUrl(JsonObject envelope) {
        JsonObject repository = McpMarketplaceJson.getObject(envelope, "repository");
        String repositoryUrl = McpMarketplaceJson.getString(repository, "url");
        if (repositoryUrl != null) {
            return repositoryUrl;
        }
        JsonObject versionDetail = McpMarketplaceJson.getObject(envelope, "version_detail");
        JsonObject nestedRepository = McpMarketplaceJson.getObject(versionDetail, "repository");
        return McpMarketplaceJson.getString(nestedRepository, "url");
    }

    private static String normalizeRegistryType(String registryType) {
        if (registryType == null) {
            return "";
        }
        String normalized = registryType.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("npm")) {
            return "npm";
        }
        if (normalized.contains("pypi") || normalized.contains("python") || normalized.contains("uv")) {
            return "pypi";
        }
        if (normalized.contains("docker") || normalized.contains("oci")) {
            return "docker";
        }
        return normalized;
    }

    private static String normalizeRemoteType(String transportType) {
        if (transportType == null) {
            return "http";
        }
        String lower = transportType.toLowerCase(Locale.ROOT);
        if (lower.contains("sse")) {
            return "sse";
        }
        return "http";
    }

    private static String shortName(String name) {
        if (name == null) {
            return "";
        }
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    private static String firstValue(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static void appendMetadata(StringBuilder description, String value) {
        if (description.length() > 0) {
            description.append(" | ");
        }
        description.append(value);
    }

    private static final class PackageDefinition {
        private final String registryType;
        private final String name;
        private final String version;

        private PackageDefinition(String registryType, String name, String version) {
            this.registryType = registryType;
            this.name = name;
            this.version = version;
        }

        static PackageDefinition from(JsonObject object, String fallbackName) {
            String packageName = firstValue(McpMarketplaceJson.getString(object, "name", "identifier"), fallbackName);
            return new PackageDefinition(
                McpMarketplaceJson.getString(object, "registry_type", "registryType", "type"),
                packageName,
                McpMarketplaceJson.getString(object, "version")
            );
        }

        String installName() {
            if (version == null || version.trim().isEmpty() || name.contains("@")) {
                return name;
            }
            return name + "@" + version;
        }
    }

    private static final class VariableDefinition {
        private final String name;
        private final String defaultValue;

        private VariableDefinition(String name, String defaultValue) {
            this.name = name;
            this.defaultValue = defaultValue;
        }

        static VariableDefinition from(JsonObject object) {
            return new VariableDefinition(
                McpMarketplaceJson.getString(object, "name"),
                McpMarketplaceJson.getString(object, "default", "defaultValue")
            );
        }
    }

    private static final class HeaderDefinition {
        private final String name;

        private HeaderDefinition(String name) {
            this.name = name;
        }

        static HeaderDefinition from(JsonObject object) {
            return new HeaderDefinition(McpMarketplaceJson.getString(object, "name"));
        }
    }
}
