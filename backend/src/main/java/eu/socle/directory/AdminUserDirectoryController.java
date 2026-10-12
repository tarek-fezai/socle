// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.directory;

import eu.socle.directory.DirectoryDtos.AdminUsersResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Liste admin paginée — {@code docs/teams-and-access.md} § 2.2. PAT interdit via /admin/**. */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserDirectoryController {

    private final DirectoryService directoryService;

    public AdminUserDirectoryController(DirectoryService directoryService) {
        this.directoryService = directoryService;
    }

    @GetMapping
    public AdminUsersResponse list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String platformRole,
            @RequestParam(required = false) UUID groupId,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Integer limit
    ) {
        return directoryService.listAdmin(q, status, platformRole, groupId, offset, limit);
    }
}
