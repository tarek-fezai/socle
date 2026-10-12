// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.directory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DirectoryDtos {

    private DirectoryDtos() {}

    /** Réponse minimale — exactement 4 champs ({@code docs/teams-and-access.md} § 2.1). */
    @io.swagger.v3.oas.annotations.media.Schema(name = "DirectoryUser")
    public record DirectoryUser(UUID id, String displayName, String email, String initials) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "DirectoryUsersResponse")
    public record DirectoryUsersResponse(List<DirectoryUser> users) {}

    public record AdminTeamRef(UUID id, String name, String role) {}

    public record AdminUserRow(
            UUID id,
            String displayName,
            String email,
            String initials,
            List<String> platformRoles,
            List<AdminTeamRef> teams,
            String status,
            Instant lastActivityAt
    ) {}

    @io.swagger.v3.oas.annotations.media.Schema(name = "AdminUsersResponse")
    public record AdminUsersResponse(
            List<AdminUserRow> users,
            int total,
            int activeCount,
            int teamCount,
            int offset,
            int limit
    ) {}
}
