package com.documind.core.document;

import com.documind.core.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.io.InputStream;

@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final S3Client s3;
    private final String bucket;

    public StorageService(S3Client s3, AppProperties props) {
        this.s3 = s3;
        this.bucket = props.storage().bucket();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureBucket() {
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException e) {
            s3.createBucket(b -> b.bucket(bucket));
            log.info("Created bucket {}", bucket);
        } catch (Exception e) {
            log.warn("Could not check bucket {}: {}", bucket, e.getMessage());
        }
    }

    public void put(String key, byte[] content) {
        s3.putObject(b -> b.bucket(bucket).key(key).contentType("application/pdf"), RequestBody.fromBytes(content));
    }

    public InputStream get(String key) {
        ResponseInputStream<GetObjectResponse> in = s3.getObject(b -> b.bucket(bucket).key(key));
        return in;
    }

    public void delete(String key) {
        s3.deleteObject(b -> b.bucket(bucket).key(key));
    }
}
