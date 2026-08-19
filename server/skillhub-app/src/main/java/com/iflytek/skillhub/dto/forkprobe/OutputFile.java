package com.iflytek.skillhub.dto.forkprobe;

/**
 * A single file produced by a skill during sandboxed execution and transferred
 * out of the sandbox so the user can download it.
 *
 * @param name          file name (basename only, no path)
 * @param sizeBytes     size of the file in bytes
 * @param contentType   MIME type when known, else {@code application/octet-stream}
 * @param contentBase64 base64-encoded file bytes — the frontend renders this as a
 *                      download link (data URI / Blob), so no file server is needed
 */
public record OutputFile(
        String name,
        long sizeBytes,
        String contentType,
        String contentBase64
) {}
