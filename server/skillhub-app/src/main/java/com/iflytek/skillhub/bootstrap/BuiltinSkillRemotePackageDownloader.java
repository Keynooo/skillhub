package com.iflytek.skillhub.bootstrap;

import com.iflytek.skillhub.config.SkillPublishProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

@Component
public class BuiltinSkillRemotePackageDownloader {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    static final String ALLOWED_HOST = "bjcdn.openstorage.cn";

    private static final Logger log = LoggerFactory.getLogger(BuiltinSkillRemotePackageDownloader.class);
    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private final long maxPackageSize;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final List<String> devAllowedHosts;
    private final String devPackageBaseUrl;

    @Autowired
    public BuiltinSkillRemotePackageDownloader(SkillPublishProperties properties,
                                               BuiltinSkillProperties builtinSkillProperties) {
        this(
                properties,
                builtinSkillProperties,
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                REQUEST_TIMEOUT
        );
    }

    BuiltinSkillRemotePackageDownloader(SkillPublishProperties properties,
                                        BuiltinSkillProperties builtinSkillProperties,
                                        HttpClient httpClient) {
        this(properties, builtinSkillProperties, httpClient, REQUEST_TIMEOUT);
    }

    BuiltinSkillRemotePackageDownloader(
            SkillPublishProperties properties,
            BuiltinSkillProperties builtinSkillProperties,
            HttpClient httpClient,
            Duration requestTimeout) {
        this.maxPackageSize = properties.getMaxPackageSize();
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
        this.devAllowedHosts = properties.getDevAllowedHosts();
        this.devPackageBaseUrl = builtinSkillProperties.getDevPackageBaseUrl();
    }

    public Optional<byte[]> download(URI uri) {
        // First try the primary URL
        Optional<byte[]> result = downloadInternal(uri);
        if (result.isPresent()) {
            return result;
        }

        // Fallback to dev package base URL if configured
        if (devPackageBaseUrl != null && !devPackageBaseUrl.isBlank()) {
            String filename = extractFilename(uri);
            if (filename != null) {
                URI fallbackUri = URI.create(devPackageBaseUrl + "/" + filename);
                log.info("Retrying built-in skill package download from dev fallback: {}", safeUrl(fallbackUri));
                return downloadInternal(fallbackUri);
            }
        }

        return Optional.empty();
    }

    private Optional<byte[]> downloadInternal(URI uri) {
        if (!isAllowedUrl(uri)) {
            log.warn("Skipping built-in skill package download because URL is not allowed: {}", safeUrl(uri));
            return Optional.empty();
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    log.warn("Failed to download built-in skill package from {}: HTTP {}",
                            safeUrl(uri),
                            response.statusCode());
                    return Optional.empty();
                }
                return readBoundedWithTimeout(body, uri);
            }
        } catch (IOException ex) {
            log.warn("Failed to download built-in skill package from {}: {}", safeUrl(uri), ex.getMessage());
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while downloading built-in skill package from {}", safeUrl(uri));
            return Optional.empty();
        } catch (RuntimeException ex) {
            log.warn("Failed to download built-in skill package from {}: {}", safeUrl(uri), ex.getMessage());
            return Optional.empty();
        }
    }

    private static String extractFilename(URI uri) {
        String path = uri.getPath();
        if (path == null) {
            return null;
        }
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    HttpClient httpClient() {
        return httpClient;
    }

    boolean isAllowedUrl(URI uri) {
        if (uri == null) {
            return false;
        }
        if (uri.getRawUserInfo() != null) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);

        // In dev/local profiles, allow configured hosts with HTTP (any port)
        if (isDevAllowedHost(normalizedHost)) {
            String scheme = uri.getScheme();
            return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
        }

        // Production: strict HTTPS only, default port only
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        int port = uri.getPort();
        if (port != -1 && port != 443) {
            return false;
        }
        if (isLocalOrLiteralHost(normalizedHost)) {
            return false;
        }
        return normalizedHost.equals(ALLOWED_HOST) || normalizedHost.endsWith("." + ALLOWED_HOST);
    }

    private boolean isDevAllowedHost(String normalizedHost) {
        if (devAllowedHosts == null) {
            return false;
        }
        for (String devHost : devAllowedHosts) {
            if (normalizedHost.equals(devHost)) {
                return true;
            }
        }
        return false;
    }

    private Optional<byte[]> readBounded(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long totalRead = 0;
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > maxPackageSize) {
                log.warn("Built-in skill package download exceeded max package size: {} bytes (max: {})",
                        totalRead,
                        maxPackageSize);
                return Optional.empty();
            }
            outputStream.write(buffer, 0, read);
        }
        return Optional.of(outputStream.toByteArray());
    }

    private Optional<byte[]> readBoundedWithTimeout(InputStream inputStream, URI uri) throws IOException {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<Optional<byte[]>> future = executor.submit(() -> readBounded(inputStream));
        try {
            return future.get(Math.max(1, requestTimeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            closeQuietly(inputStream);
            future.cancel(true);
            log.warn("Timed out while downloading built-in skill package body from {} after {}",
                    safeUrl(uri),
                    requestTimeout);
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            closeQuietly(inputStream);
            future.cancel(true);
            log.warn("Interrupted while reading built-in skill package body from {}", safeUrl(uri));
            return Optional.empty();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Failed to read built-in skill package body", cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void closeQuietly(InputStream inputStream) {
        try {
            inputStream.close();
        } catch (IOException ignored) {
            // Best-effort cleanup after timeout/interruption.
        }
    }

    private static boolean isLocalOrLiteralHost(String host) {
        return "localhost".equals(host)
                || IPV4_LITERAL.matcher(host).matches()
                || host.contains(":");
    }

    private static String safeUrl(URI uri) {
        if (uri == null) {
            return "<null>";
        }
        String host = uri.getHost();
        String path = uri.getRawPath();
        return (host == null ? "<unknown-host>" : host) + (path == null ? "" : path);
    }
}
