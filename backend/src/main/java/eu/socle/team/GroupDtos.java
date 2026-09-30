package eu.socle.team;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public final class GroupDtos {

    private GroupDtos() {}

    public record CreateGroupRequest(@NotBlank String name) {}

    public record UpdateGroupRequest(@NotBlank String name) {}

    public record MemberView(UUID userId, String email, String displayName) {}

    public record GroupView(
            UUID id,
            String name,
            UUID createdBy,
            String createdAt,
            int memberCount,
            boolean canManage,
            List<MemberView> members
    ) {}

    public record AddMemberRequest(@NotNull UUID userId) {}
}
