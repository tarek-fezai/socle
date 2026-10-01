// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "user_identities")
@IdClass(UserIdentityEntity.Pk.class)
public class UserIdentityEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Column(nullable = false)
    private String issuer;

    @Id
    @Column(nullable = false)
    private String subject;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    @Column(name = "linked_by")
    private UUID linkedBy;

    @PrePersist
    void onCreate() {
        if (linkedAt == null) {
            linkedAt = Instant.now();
        }
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }

    public void setLinkedAt(Instant linkedAt) {
        this.linkedAt = linkedAt;
    }

    public UUID getLinkedBy() {
        return linkedBy;
    }

    public void setLinkedBy(UUID linkedBy) {
        this.linkedBy = linkedBy;
    }

    public static class Pk implements Serializable {
        private UUID userId;
        private String issuer;
        private String subject;

        public Pk() {}

        public Pk(UUID userId, String issuer, String subject) {
            this.userId = userId;
            this.issuer = issuer;
            this.subject = subject;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Pk pk)) {
                return false;
            }
            return Objects.equals(userId, pk.userId)
                    && Objects.equals(issuer, pk.issuer)
                    && Objects.equals(subject, pk.subject);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, issuer, subject);
        }
    }
}
