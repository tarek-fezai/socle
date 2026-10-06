// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.blob;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;
import java.time.Duration;

/**
 * Même contrat que {@link LocalBlobStoreContractTest}, contre {@link S3BlobStore} et un endpoint
 * S3-compatible jetable : LocalStack (service S3 uniquement). {@code minio/minio} n'est plus publié
 * sur Docker Hub ; Garage (image de déploiement) exige un bootstrap de layout/clés trop lourd pour un test.
 * Ignoré si Docker est indisponible.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("dockerAvailable")
class S3BlobStoreContractTest extends BlobStoreContractTest {

    static final String ACCESS_KEY = "test";
    static final String SECRET_KEY = "test";
    static final String BUCKET = "socle-contract";

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> s3 = new GenericContainer<>("localstack/localstack:3")
            .withEnv("SERVICES", "s3")
            .withExposedPorts(4566)
            .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566)
                    .withStartupTimeout(Duration.ofSeconds(120)));

    static S3BlobStore s3Store;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @BeforeAll
    static void createBucketAndStore() {
        String endpoint = "http://" + s3.getHost() + ":" + s3.getMappedPort(4566);
        try (S3Client admin = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .forcePathStyle(true)
                .build()) {
            admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }

        BlobProperties.S3 props = new BlobProperties.S3();
        props.setEndpoint(endpoint);
        props.setRegion("us-east-1");
        props.setBucket(BUCKET);
        props.setAccessKey(ACCESS_KEY);
        props.setSecretKey(SECRET_KEY);
        props.setPathStyle(true);
        props.setServerSideEncryption(true); // défaut prod : SSE-S3 (AES256)
        s3Store = new S3BlobStore(props);
    }

    @AfterAll
    static void closeStore() {
        if (s3Store != null) {
            s3Store.close();
        }
    }

    @Override
    BlobStore store() {
        return s3Store;
    }
}
