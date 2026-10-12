// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.directory;

import eu.socle.config.SecurityWebMvcTest;
import eu.socle.directory.DirectoryDtos.AdminUsersResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SecurityWebMvcTest(controllers = AdminUserDirectoryController.class)
class AdminUserDirectoryControllerWebMvcTest {

    @Autowired MockMvc mockMvc;
    @MockBean DirectoryService directoryService;

    @Test
    void nonAdmin_403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_contributeur"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_ok() throws Exception {
        when(directoryService.listAdmin(isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new AdminUsersResponse(List.of(), 0, 0, 0, 0, 50));

        mockMvc.perform(get("/api/v1/admin/users")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_administrateur-systeme"))))
                .andExpect(status().isOk());
    }
}
