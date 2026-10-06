// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.blob;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Choix d'instance exclusif pour les binaires — {@code local} (défaut) ou {@code s3}
 * (endpoint auto-hébergé : Garage, SeaweedFS…). Aucun endpoint AWS par défaut.
 */
@Component
@ConfigurationProperties(prefix = "socle.blob")
public class BlobProperties {

    private static final Set<String> ALLOWED = Set.of("local", "s3");

    /** {@code local} | {@code s3}. */
    private String provider = "local";

    private final Local local = new Local();
    private final S3 s3 = new S3();

    @PostConstruct
    void validate() {
        if (provider == null || provider.isBlank()) {
            throw new IllegalStateException(
                    "socle.blob.provider est obligatoire (local|s3)");
        }
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(normalized)) {
            throw new IllegalStateException(
                    "socle.blob.provider invalide: '" + provider + "' (attendu: local|s3)");
        }
        this.provider = normalized;
        if (isS3()) {
            require(s3.endpoint, "socle.blob.s3.endpoint (SOCLE_BLOB_S3_ENDPOINT)");
            require(s3.bucket, "socle.blob.s3.bucket (SOCLE_BLOB_S3_BUCKET)");
            require(s3.accessKey, "socle.blob.s3.access-key (SOCLE_BLOB_S3_ACCESS_KEY)");
            require(s3.secretKey, "socle.blob.s3.secret-key (SOCLE_BLOB_S3_SECRET_KEY)");
            if (s3.region == null || s3.region.isBlank()) {
                s3.region = "us-east-1";
            }
        }
        if (isLocal() && (local.dir == null || local.dir.isBlank())) {
            throw new IllegalStateException(
                    "socle.blob.local.dir est obligatoire (SOCLE_BLOB_LOCAL_DIR)");
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " est obligatoire lorsque socle.blob.provider=s3");
        }
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Local getLocal() {
        return local;
    }

    public S3 getS3() {
        return s3;
    }

    public boolean isLocal() {
        return "local".equals(provider);
    }

    public boolean isS3() {
        return "s3".equals(provider);
    }

    public static class Local {
        /** Répertoire racine des blobs (écriture atomique temp+rename). */
        private String dir = "./data/blobs";

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }
    }

    public static class S3 {
        /** URL du service S3-compatible (Garage, SeaweedFS…) — jamais un défaut AWS. */
        private String endpoint;
        private String region = "us-east-1";
        private String bucket;
        private String accessKey;
        private String secretKey;
        /** Path-style (requis pour Garage / MinIO locaux). */
        private boolean pathStyle = true;
        /** SSE-S3 si le service le permet ({@code AES256}). */
        private boolean serverSideEncryption = true;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public boolean isPathStyle() {
            return pathStyle;
        }

        public void setPathStyle(boolean pathStyle) {
            this.pathStyle = pathStyle;
        }

        public boolean isServerSideEncryption() {
            return serverSideEncryption;
        }

        public void setServerSideEncryption(boolean serverSideEncryption) {
            this.serverSideEncryption = serverSideEncryption;
        }
    }
}
