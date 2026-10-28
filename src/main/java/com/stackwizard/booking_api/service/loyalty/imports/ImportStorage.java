package com.stackwizard.booking_api.service.loyalty.imports;

import com.stackwizard.booking_api.config.MediaS3Properties;
import org.springframework.stereotype.Component;
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

/** Private S3 area for import files ({@code <app-alias>/loyalty-imports/...}, never under the public prefix). */
@Component
public class ImportStorage {
    static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(30);

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final MediaS3Properties props;

    public ImportStorage(S3Client s3Client, S3Presigner presigner, MediaS3Properties props) {
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.props = props;
    }

    public String keyFor(Long tenantId, Long batchId) {
        return props.getAppAlias() + "/loyalty-imports/" + tenantId + "/" + batchId + ".csv";
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
