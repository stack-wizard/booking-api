package com.stackwizard.booking_api.service.loyalty.imports;

import com.stackwizard.booking_api.config.MediaS3Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

/**
 * S3 area for import files: {@code <loyalty.import.s3-key-prefix>/<tenant>/<batch>.csv}. The default
 * ({@code <app-alias>/loyalty-imports}) is a private prefix; an environment whose IAM policy only allows the media
 * prefix can point it there (see application-dev.yaml) until the policy is widened.
 */
@Component
public class ImportStorage {
    static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(30);

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final MediaS3Properties props;
    private final String prefix;

    public ImportStorage(S3Client s3Client, S3Presigner presigner, MediaS3Properties props,
                         @Value("${loyalty.import.s3-key-prefix:}") String keyPrefix) {
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.props = props;
        String configured = keyPrefix == null ? "" : keyPrefix.replaceAll("^/+|/+$", "");
        this.prefix = configured.isEmpty() ? props.getAppAlias() + "/loyalty-imports" : configured;
    }

    public String keyFor(Long tenantId, Long batchId) {
        return prefix + "/" + tenantId + "/" + batchId + ".csv";
    }

    public String presignPut(String key) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(props.getBucket())
                .key(key)
                .contentType("text/csv")
                .build();
        return presigner.presignPutObject(PutObjectPresignRequest.builder()
                        .signatureDuration(UPLOAD_URL_TTL)
                        .putObjectRequest(put)
                        .build())
                .url().toString();
    }

    /** Server-side upload for a client that cannot (or should not) PUT to the presigned URL itself. */
    public void put(String key, byte[] content, String contentType) {
        s3Client.putObject(PutObjectRequest.builder()
                        .bucket(props.getBucket())
                        .key(key)
                        .contentType(contentType == null ? "text/csv" : contentType)
                        .build(),
                RequestBody.fromBytes(content));
    }

    public Optional<Long> size(String key) {
        try {
            HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder().bucket(props.getBucket()).key(key).build());
            return Optional.of(head.contentLength());
        } catch (NoSuchKeyException ex) {
            return Optional.empty();
        } catch (software.amazon.awssdk.services.s3.model.S3Exception ex) {
            if (ex.statusCode() == 404) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    public InputStream open(String key) {
        return s3Client.getObject(GetObjectRequest.builder().bucket(props.getBucket()).key(key).build());
    }
}
