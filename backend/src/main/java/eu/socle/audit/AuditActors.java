package eu.socle.audit;

import java.util.Optional;
import java.util.UUID;

/**
 * Pont statique vers {@link AuditActorResolver} pour les services qui n'ont pas
 * encore le Jwt en paramètre. Préférer l'injection de {@link AuditActorResolver}.
 */
public final class AuditActors {

    private static volatile AuditActorResolver resolver;

    private AuditActors() {}

    static void bind(AuditActorResolver r) {
        resolver = r;
    }

    public static Optional<UUID> currentUserId() {
        AuditActorResolver r = resolver;
        if (r == null) {
            return Optional.empty();
        }
        return r.currentUserId();
    }
}
