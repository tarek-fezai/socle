// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.blob;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.io.InputStream;
import java.net.URI;
import java.util.Optional;

/**
 * Client S3-compatible (Garage, SeaweedFS, MinIO…) — endpoint obligatoire, path-style,
 * aucun défaut AWS. SSE-S3 activé si configuré et supporté par le service.
 */
public class S3BlobStore implements BlobStore, AutoCloseable {

    private final S3Client client;
    private final String bucket;
    private final boolean serverSideEncryption;

    public S3BlobStore(BlobProperties.S3 props) {
        this.bucket = props.getBucket();
        this.serverSideEncryption = props.isServerSideEncryption();
        // Path-style : une seule source (forcePathStyle) — le SDK refuse S3Configuration + forcePathStyle.
        this.client = S3Client.builder()
                .endpointOverride(URI.create(props.getEndpoint()))
                .region(Region.of(props.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .forcePathStyle(props.isPathStyle())
                .build();
    }

    /** Constructeur tests — client injecté. */
    S3BlobStore(S3Client client, String bucket, boolean serverSideEncryption) {
        this.client = client;
        this.bucket = bucket;
        this.serverSideEncryption = serverSideEncryption;
    }

    @Override
    public void put(String key, InputStream content, long size, String mediaType) {
        String valid = BlobKeys.requireValid(key);
        PutObjectRequest.Builder builder = PutObjectRequest.builder()
                .bucket(bucket)
                .key(valid)
                .contentLength(size);
        if (mediaType != null && !mediaType.isBlank()) {
            builder.contentType(mediaType);
        }
        if (serverSideEncryption) {
            builder.serverSideEncryption(ServerSideEncryption.AES256);
        }
        client.putObject(builder.build(), RequestBody.fromInputStream(content, size));
    }

    @Override
    public Optional<BlobObject> get(String key, Optional<ByteRange> range) {
        String valid = BlobKeys.requireValid(key);
        try {
            long total = client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(valid).build()).contentLength();
            GetObjectRequest.Builder builder = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(valid);
            Optional<ByteRange> applied = Optional.empty();
            long contentLength = total;
            if (range.isPresent()) {
                ByteRange r = range.get();
                long from = r.from();
                long to = Math.min(r.to(), total - 1);
                if (from >= total) {
                    return Optional.empty();
                }
                builder.range("bytes=" + from + "-" + to);
                applied = Optional.of(new ByteRange(from, to));
                contentLength = to - from + 1;
            }
            var response = client.getObject(builder.build());
            String mediaType = response.response().contentType();
            return Optional.of(new BlobObject(response, contentLength, mediaType, applied, total));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String key) {
        client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(BlobKeys.requireValid(key))
                .build());
    }

    @Override
    public boolean exists(String key) {
        try {
            client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(BlobKeys.requireValid(key))
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
