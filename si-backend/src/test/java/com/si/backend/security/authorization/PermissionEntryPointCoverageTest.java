package com.si.backend.security.authorization;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.common.Constants;
import com.si.backend.config.AuthorizationOpenApiCustomizer;
import com.si.backend.controller.BotApiProxyController;
import io.swagger.v3.oas.models.Operation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Release gate that requires every externally invokable entry point to have an authorization contract.
 */
class PermissionEntryPointCoverageTest {

    private static final String APPLICATION_PACKAGE = "com.si.backend";
    private static final String CONTROLLER_PACKAGE = APPLICATION_PACKAGE + ".controller";
    private static final String CATALOG_RESOURCE = "authorization-non-http-entrypoints.json";
    private static final String BOT_PROXY_PREFIX = "/bot-api";
    private static final Pattern WS_REGISTRATION_PATTERN = Pattern.compile(
            "registry\\.addHandler\\([^,]+,\\s*([^\\)]+)\\)",
            Pattern.MULTILINE
    );
    private static final Pattern CSHARP_CLASS_ROUTE_PATTERN = Pattern.compile("\\[Route\\(\"([^\"]+)\"\\)\\]");
    private static final Pattern CSHARP_HTTP_BLOCK_PATTERN = Pattern.compile(
            "\\[([^\\]]*Http(?:Get|Post|Put|Delete|Patch)[^\\]]*)\\]\\s*"
                    + "public\\s+(?:async\\s+)?[^\\s]+\\s+\\w+\\s*\\(",
            Pattern.DOTALL
    );
    private static final Pattern CSHARP_HTTP_ATTRIBUTE_PATTERN = Pattern.compile(
            "Http(Get|Post|Put|Delete|Patch)(?:\\(\"([^\"]*)\"\\))?"
    );
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void everyEntryPointHasAuthorizationMetadataAndIsIncludedInTheGeneratedReport() throws Exception {
        List<EntryPoint> httpEntries = discoverHttpEntries();
        List<EntryPoint> catalogEntries = readCatalog();

        assertCatalogIsValid(catalogEntries);
        assertDiscoveredEntriesMatchCatalog("FRAMEWORK_HTTP", discoverFrameworkHttpEntries(), catalogEntries);
        assertDiscoveredEntriesMatchCatalog("WEBSOCKET", discoverWebSocketEntries(), catalogEntries);
        assertDiscoveredEntriesMatchCatalog("BOT_PROXY_OPERATION", discoverBotProxyEntries(), catalogEntries);
        assertDiscoveredEntriesMatchCatalog("CSHARP_BOT_HTTP", discoverCsharpBotEntries(), catalogEntries);
        assertDiscoveredEntriesMatchCatalog("SCHEDULED", discoverAnnotatedTaskEntries(Scheduled.class), catalogEntries);
        assertDiscoveredEntriesMatchCatalog("ASYNC", discoverAnnotatedTaskEntries(Async.class), catalogEntries);

        List<EntryPoint> reportEntries = new ArrayList<>(httpEntries);
        reportEntries.addAll(catalogEntries);
        reportEntries.sort(Comparator.comparing(EntryPoint::kind)
                .thenComparing(EntryPoint::path)
                .thenComparing(EntryPoint::method));
        writeReport(reportEntries);
    }

    @Test
    void swaggerCustomizerPublishesResolvedAuthorizationMetadata() throws Exception {
        Method method = SwaggerExample.class.getDeclaredMethod("protectedOperation");
        HandlerMethod handlerMethod = new HandlerMethod(new SwaggerExample(), method);

        Operation operation = new AuthorizationOpenApiCustomizer().customize(new Operation(), handlerMethod);

        assertEquals("USER", operation.getExtensions().get(AuthorizationOpenApiCustomizer.EXT_IDENTITY));
        assertEquals("SUMMARY_MANAGE", operation.getExtensions().get(AuthorizationOpenApiCustomizer.EXT_PERMISSION));
        assertEquals("OWN", operation.getExtensions().get(AuthorizationOpenApiCustomizer.EXT_RESOURCE_SCOPE));
        assertEquals(List.of(200, 401, 404),
                operation.getExtensions().get(AuthorizationOpenApiCustomizer.EXT_EXPECTED_STATUSES));
    }

    @Test
    void swaggerCustomizerDoesNotInventMetadataForAnUnclassifiedOperation() throws Exception {
        Method method = SwaggerExample.class.getDeclaredMethod("unclassifiedOperation");
        HandlerMethod handlerMethod = new HandlerMethod(new SwaggerExample(), method);

        Operation operation = new AuthorizationOpenApiCustomizer().customize(new Operation(), handlerMethod);

        assertNull(operation.getExtensions());
    }

    @Test
    void unclassifiedOrStaleNonHttpEntryFailsTheReleaseGate() {
        EntryPoint unclassified = new EntryPoint(
                "WEBSOCKET",
                "WS",
                "/ws/new-unclassified",
                null,
                null,
                null,
                List.of(),
                "example"
        );
        EntryPoint stale = new EntryPoint(
                "WEBSOCKET",
                "WS",
                "/ws/stale",
                "USER",
                "INTERPRETATION_OPERATE",
                "OWN",
                List.of(101),
                "example"
        );

        assertThrows(AssertionError.class,
                () -> assertDiscoveredEntriesMatchCatalog("WEBSOCKET", List.of(unclassified), List.of()));
        assertThrows(AssertionError.class,
                () -> assertDiscoveredEntriesMatchCatalog("WEBSOCKET", List.of(), List.of(stale)));
    }

    private List<EntryPoint> discoverHttpEntries() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<EntryPoint> entries = new ArrayList<>();
        List<String> missingAuthorization = new ArrayList<>();
        for (BeanDefinition beanDefinition : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
            Class<?> controllerClass = loadClass(beanDefinition.getBeanClassName());
            ReflectionUtils.doWithMethods(controllerClass, method -> {
                RequestMapping methodMapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (methodMapping == null) {
                    return;
                }
                AuthorizationSpec spec = resolveAuthorizationSpec(controllerClass, method);
                if (spec == null) {
                    missingAuthorization.add(controllerClass.getName() + "#" + method.getName());
                    return;
                }
                assertOpenApiExtensions(controllerClass, method, spec);
                entries.addAll(toHttpEntries(controllerClass, method, methodMapping, spec));
            }, method -> Modifier.isPublic(method.getModifiers()));
        }
        assertTrue(missingAuthorization.isEmpty(),
                "Unclassified HTTP handlers. Add @AuthorizationSpec: " + missingAuthorization);
        assertFalse(entries.isEmpty(), "No HTTP controller entry points were discovered");
        assertNoDuplicateKeys(entries, "HTTP");
        return entries;
    }

    private AuthorizationSpec resolveAuthorizationSpec(Class<?> controllerClass, Method method) {
        AuthorizationSpec methodSpec = AnnotatedElementUtils.findMergedAnnotation(method, AuthorizationSpec.class);
        return methodSpec != null
                ? methodSpec
                : AnnotatedElementUtils.findMergedAnnotation(controllerClass, AuthorizationSpec.class);
    }

    private void assertOpenApiExtensions(Class<?> controllerClass, Method method, AuthorizationSpec spec) {
        Operation operation = AuthorizationOpenApiCustomizer.applyAuthorizationExtensions(new Operation(), spec);
        Map<String, Object> extensions = operation.getExtensions();
        assertNotNull(extensions, controllerClass.getName() + "#" + method.getName() + " has no OpenAPI extensions");
        assertEquals(spec.identity().name(), extensions.get(AuthorizationOpenApiCustomizer.EXT_IDENTITY));
        assertEquals(spec.permission().name(), extensions.get(AuthorizationOpenApiCustomizer.EXT_PERMISSION));
        assertEquals(spec.scope().name(), extensions.get(AuthorizationOpenApiCustomizer.EXT_RESOURCE_SCOPE));
        assertEquals(Arrays.stream(spec.expectedStatuses()).boxed().toList(),
                extensions.get(AuthorizationOpenApiCustomizer.EXT_EXPECTED_STATUSES));
    }

    private List<EntryPoint> toHttpEntries(
            Class<?> controllerClass,
            Method method,
            RequestMapping methodMapping,
            AuthorizationSpec spec
    ) {
        RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(controllerClass, RequestMapping.class);
        Set<String> classPaths = mappingPaths(classMapping);
        Set<String> methodPaths = mappingPaths(methodMapping);
        Set<String> methods = mappingMethods(methodMapping);
        List<EntryPoint> entries = new ArrayList<>();
        for (String classPath : classPaths) {
            for (String methodPath : methodPaths) {
                for (String httpMethod : methods) {
                    entries.add(fromSpec(
                            "HTTP",
                            httpMethod,
                            joinPath(classPath, methodPath),
                            spec,
                            controllerClass.getName() + "#" + method.getName()
                    ));
                }
            }
        }
        return entries;
    }

    private Set<String> mappingPaths(RequestMapping mapping) {
        if (mapping == null || mapping.path().length == 0) {
            return Set.of("");
        }
        return Arrays.stream(mapping.path()).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> mappingMethods(RequestMapping mapping) {
        if (mapping.method().length == 0) {
            return Set.of("ANY");
        }
        return Arrays.stream(mapping.method())
                .map(RequestMethod::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<EntryPoint> discoverWebSocketEntries() throws Exception {
        Path configSource = sourceRoot().resolve("main/java/com/si/backend/config/WebSocketConfig.java");
        String source = Files.readString(configSource, StandardCharsets.UTF_8);
        int registrationCount = countOccurrences(source, "registry.addHandler(");
        List<EntryPoint> entries = new ArrayList<>();
        Matcher matcher = WS_REGISTRATION_PATTERN.matcher(source);
        while (matcher.find()) {
            String pathExpression = matcher.group(1).trim();
            String path = resolveWebSocketPath(pathExpression);
            entries.add(new EntryPoint("WEBSOCKET", "WS", path, null, null, null, List.of(),
                    WebSocketSource.class.getName()));
        }
        assertEquals(registrationCount, entries.size(),
                "Every registry.addHandler registration must use a resolvable literal or Constants field");
        return entries;
    }

    private List<EntryPoint> discoverFrameworkHttpEntries() {
        Properties properties = loadApplicationProperties();
        List<EntryPoint> entries = new ArrayList<>();
        String exposedActuatorEndpoints = properties.getProperty("management.endpoints.web.exposure.include", "");
        String actuatorBasePath = properties.getProperty("management.endpoints.web.base-path", "/actuator");
        for (String endpoint : splitCommaSeparated(exposedActuatorEndpoints)) {
            assertFalse(endpoint.equals("*"),
                    "Actuator wildcard exposure is not allowed; register explicit framework HTTP entry points");
            entries.add(unclassifiedEntry("FRAMEWORK_HTTP", "GET", joinPath(actuatorBasePath, endpoint),
                    "si-backend/src/main/resources/application.yml"));
        }

        String apiDocsPath = properties.getProperty("springdoc.api-docs.path");
        if (apiDocsPath != null && !apiDocsPath.isBlank()) {
            entries.add(unclassifiedEntry("FRAMEWORK_HTTP", "GET", apiDocsPath,
                    "si-backend/src/main/resources/application.yml"));
        }
        String swaggerUiPath = properties.getProperty("springdoc.swagger-ui.path");
        if (swaggerUiPath != null && !swaggerUiPath.isBlank()) {
            entries.add(unclassifiedEntry("FRAMEWORK_HTTP", "GET", swaggerUiPath,
                    "si-backend/src/main/resources/application.yml"));
        }
        return entries;
    }

    private Properties loadApplicationProperties() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(sourceRoot().resolve("main/resources/application.yml")));
        Properties properties = yaml.getObject();
        assertNotNull(properties, "Unable to load application.yml");
        return properties;
    }

    private Set<String> splitCommaSeparated(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String resolveWebSocketPath(String expression) throws Exception {
        if (expression.startsWith("\"") && expression.endsWith("\"")) {
            return normalizePath(expression.substring(1, expression.length() - 1));
        }
        if (expression.startsWith("Constants.")) {
            Field field = Constants.class.getField(expression.substring("Constants.".length()));
            return normalizePath((String) field.get(null));
        }
        throw new IllegalArgumentException("Unsupported WebSocket path expression: " + expression);
    }

    @SuppressWarnings("unchecked")
    private List<EntryPoint> discoverBotProxyEntries() throws Exception {
        Field operationsField = BotApiProxyController.class.getDeclaredField("ALLOWED_OPERATIONS");
        operationsField.setAccessible(true);
        Map<String, Set<HttpMethod>> operations = (Map<String, Set<HttpMethod>>) operationsField.get(null);
        List<EntryPoint> entries = new ArrayList<>();
        for (Map.Entry<String, Set<HttpMethod>> operation : operations.entrySet()) {
            for (HttpMethod method : operation.getValue()) {
                entries.add(new EntryPoint(
                        "BOT_PROXY_OPERATION",
                        method.name(),
                        normalizePath(BOT_PROXY_PREFIX + operation.getKey()),
                        null,
                        null,
                        null,
                        List.of(),
                        BotApiProxyController.class.getName()
                ));
            }
        }
        return entries;
    }

    private List<EntryPoint> discoverCsharpBotEntries() throws IOException {
        Path controllersDirectory = repositoryRoot().resolve("bot/CallingBotSample/Controllers");
        assertTrue(Files.isDirectory(controllersDirectory),
                "Production C# Bot controllers directory is missing: " + controllersDirectory);
        List<EntryPoint> entries = new ArrayList<>();
        try (var files = Files.list(controllersDirectory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith("Controller.cs")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher classRouteMatcher = CSHARP_CLASS_ROUTE_PATTERN.matcher(source);
                assertTrue(classRouteMatcher.find(), "C# Bot controller is missing [Route]: " + file);
                String classRoute = classRouteMatcher.group(1);
                Matcher blockMatcher = CSHARP_HTTP_BLOCK_PATTERN.matcher(source);
                while (blockMatcher.find()) {
                    Matcher attributeMatcher = CSHARP_HTTP_ATTRIBUTE_PATTERN.matcher(blockMatcher.group(1));
                    while (attributeMatcher.find()) {
                        String method = attributeMatcher.group(1).toUpperCase();
                        String methodRoute = attributeMatcher.group(2) == null ? "" : attributeMatcher.group(2);
                        entries.add(new EntryPoint(
                                "CSHARP_BOT_HTTP",
                                method,
                                joinPath(classRoute, methodRoute),
                                null,
                                null,
                                null,
                                List.of(),
                                repositoryRoot().relativize(file).toString().replace('\\', '/')
                        ));
                    }
                }
            }
        }
        assertFalse(entries.isEmpty(), "No C# Bot HTTP routes were discovered");
        return entries;
    }

    private List<EntryPoint> discoverAnnotatedTaskEntries(Class<? extends java.lang.annotation.Annotation> annotation)
            throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile("com\\.si\\.backend\\..*")));
        List<EntryPoint> entries = new ArrayList<>();
        for (BeanDefinition beanDefinition : scanner.findCandidateComponents(APPLICATION_PACKAGE)) {
            Class<?> type = loadClass(beanDefinition.getBeanClassName());
            ReflectionUtils.doWithMethods(type, method -> {
                if (AnnotatedElementUtils.findMergedAnnotation(method, annotation) != null) {
                    entries.add(new EntryPoint(
                            annotation == Scheduled.class ? "SCHEDULED" : "ASYNC",
                            "TASK",
                            type.getName() + "#" + method.getName(),
                            null,
                            null,
                            null,
                            List.of(),
                            type.getName()
                    ));
                }
            });
        }
        return entries;
    }

    private List<EntryPoint> readCatalog() throws IOException {
        ClassPathResource resource = new ClassPathResource(CATALOG_RESOURCE);
        assertTrue(resource.exists(), "Missing authorization catalog: " + CATALOG_RESOURCE);
        return OBJECT_MAPPER.readValue(resource.getInputStream(), new TypeReference<>() {
        });
    }

    private void assertCatalogIsValid(List<EntryPoint> catalogEntries) {
        assertFalse(catalogEntries.isEmpty(), "Authorization catalog must not be empty");
        assertNoDuplicateKeys(catalogEntries, "catalog");
        for (EntryPoint entry : catalogEntries) {
            assertTrue(Set.of("WEBSOCKET", "BOT_PROXY_OPERATION", "CSHARP_BOT_HTTP", "SCHEDULED", "ASYNC")
                            .contains(entry.kind())
                            || entry.kind().equals("FRAMEWORK_HTTP"),
                    "Unsupported catalog kind: " + entry.kind());
            assertNotNull(IdentityType.valueOf(entry.identity()), "Invalid identity for " + entry.key());
            assertNotNull(PermissionCode.valueOf(entry.permission()), "Invalid permission for " + entry.key());
            assertNotNull(ResourceScope.valueOf(entry.scope()), "Invalid scope for " + entry.key());
            assertFalse(entry.expectedStatuses().isEmpty(), "Expected statuses are required for " + entry.key());
            assertTrue(entry.expectedStatuses().stream().allMatch(status -> status >= 100 && status <= 599),
                    "Invalid expected status for " + entry.key());
            assertFalse(entry.source().isBlank(), "Source is required for " + entry.key());
        }
    }

    private void assertDiscoveredEntriesMatchCatalog(
            String kind,
            List<EntryPoint> discoveredEntries,
            List<EntryPoint> catalogEntries
    ) {
        Set<String> discovered = discoveredEntries.stream().map(EntryPoint::key)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> registered = catalogEntries.stream()
                .filter(entry -> entry.kind().equals(kind))
                .map(EntryPoint::key)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> unclassified = new TreeSet<>(discovered);
        unclassified.removeAll(registered);
        Set<String> stale = new TreeSet<>(registered);
        stale.removeAll(discovered);
        assertTrue(unclassified.isEmpty() && stale.isEmpty(),
                kind + " authorization catalog mismatch. Unclassified=" + unclassified + ", stale=" + stale);
    }

    private void assertNoDuplicateKeys(List<EntryPoint> entries, String label) {
        Set<String> keys = new HashSet<>();
        Set<String> duplicates = entries.stream()
                .map(EntryPoint::key)
                .filter(key -> !keys.add(key))
                .collect(Collectors.toCollection(TreeSet::new));
        assertTrue(duplicates.isEmpty(), "Duplicate " + label + " entry points: " + duplicates);
    }

    private EntryPoint fromSpec(
            String kind,
            String method,
            String path,
            AuthorizationSpec spec,
            String source
    ) {
        return new EntryPoint(
                kind,
                method,
                normalizePath(path),
                spec.identity().name(),
                spec.permission().name(),
                spec.scope().name(),
                Arrays.stream(spec.expectedStatuses()).boxed().toList(),
                source
        );
    }

    private EntryPoint unclassifiedEntry(String kind, String method, String path, String source) {
        return new EntryPoint(kind, method, normalizePath(path), null, null, null, List.of(), source);
    }

    private void writeReport(List<EntryPoint> entries) throws IOException {
        Path report = projectRoot().resolve("target/permission-entrypoints-report.json");
        Files.createDirectories(report.getParent());
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), entries);
        assertTrue(Files.size(report) > 0, "Generated authorization report is empty");
    }

    private Class<?> loadClass(String className) throws ClassNotFoundException {
        return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
    }

    private Path projectRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        return Files.isDirectory(cwd.resolve("src/main/java")) ? cwd : cwd.resolve("si-backend");
    }

    private Path sourceRoot() {
        return projectRoot().resolve("src");
    }

    private Path repositoryRoot() {
        return projectRoot().getParent();
    }

    private int countOccurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    private String joinPath(String left, String right) {
        return normalizePath(left + "/" + right);
    }

    private String normalizePath(String path) {
        String normalized = path.replaceAll("/+", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private record EntryPoint(
            String kind,
            String method,
            String path,
            String identity,
            String permission,
            String scope,
            List<Integer> expectedStatuses,
            String source
    ) {
        private String key() {
            return kind + " " + method + " " + path;
        }
    }

    private static final class WebSocketSource {
        private WebSocketSource() {
        }
    }

    private static final class SwaggerExample {

        @AuthorizationSpec(
                identity = IdentityType.USER,
                permission = PermissionCode.SUMMARY_MANAGE,
                scope = ResourceScope.OWN,
                expectedStatuses = {200, 401, 404})
        public void protectedOperation() {
        }

        public void unclassifiedOperation() {
        }
    }
}
