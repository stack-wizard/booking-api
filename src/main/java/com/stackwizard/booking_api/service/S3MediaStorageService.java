package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.config.MediaS3Properties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.time.Instant;
import java.util.Locale;

@Service
public class S3MediaStorageService implements MediaStorageService {

    private final S3Client s3Client;
    private final MediaS3Properties mediaS3Properties;

    public S3MediaStorageService(S3Client s3Client, MediaS3Properties mediaS3Properties) {
        this.s3Client = s3Client;
        this.mediaS3Properties = mediaS3Properties;
    }

    @Override
    public String upload(String category, Long tenantId, String baseName, MultipartFile file) {
        return uploadInternal(category, tenantId, baseName, file, false);
    }

    @Override
    public String uploadPublic(String category, Long tenantId, String baseName, MultipartFile file) {
        return uploadInternal(category, tenantId, baseName, file, true);
    }

    private String uploadInternal(String category, Long tenantId, String baseName, MultipartFile file, boolean publicAsset) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }
        if (!StringUtils.hasText(mediaS3Properties.getBucket())) {
            throw new IllegalStateException("media.s3.bucket must be configured");
        }

        String extension = extensionOrDefault(file.getOriginalFilename(), "bin");
        String safeBase = sanitizePathToken(baseName);
        String safeCategory = sanitizePathToken(category);
        String safeAppAlias = sanitizePathToken(mediaS3Properties.getAppAlias());
        String tenantPath = "tenants/" + tenantId;
        String key = publicAsset
                ? sanitizePrefix(mediaS3Properties.getPublicPrefix()) + "/" + safeAppAlias + "/" + tenantPath + "/" + safeCategory + "/" + safeBase + "." + extension
                : "private/" + safeAppAlias + "/" + tenantPath + "/" + safeCategory + "/" + safeBase + "-" + Instant.now().toEpochMilli() + "." + extension;

        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(mediaS3Properties.getBucket())
                    .key(key)
                    .contentType(file.getContentType())
                    .build();
            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(file.getBytes()));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read file payload", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to upload media to S3", ex);
        }

        return s3Client.utilities().getUrl(GetUrlRequest.builder()
                .bucket(mediaS3Properties.getBucket())
                .key(key)
                .build()).toExternalForm();
    }

    private String extensionOrDefault(String filename, String defaultExt) {
        if (!StringUtils.hasText(filename)) {
            return defaultExt;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return defaultExt;
        }
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return ext.isBlank() ? defaultExt : ext;
    }

    private String sanitizePathToken(String value) {
        if (!StringUtils.hasText(value)) {
            return "file";
        }
        String sanitized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-_]", "-");
        return sanitized.isBlank() ? "file" : sanitized;
    }

    private String sanitizePrefix(String value) {
        if (!StringUtils.hasText(value)) {
            return "public";
        }
        String sanitized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/_-]", "");
        sanitized = sanitized.replaceAll("/{2,}", "/");
        sanitized = sanitized.replaceAll("^/+", "").replaceAll("/+$", "");
        return sanitized.isBlank() ? "public" : sanitized;
    }
}
