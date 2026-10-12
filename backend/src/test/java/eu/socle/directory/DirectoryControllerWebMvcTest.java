// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.directory;

import eu.socle.config.SecurityWebMvcTest;
import eu.socle.directory.DirectoryDtos.DirectoryUser;
import eu.socle.directory.DirectoryDtos.DirectoryUsersResponse;
import eu.socle.user.UserEntity;
import eu.socle.user.UserSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SecurityWebMvcTest(controllers = DirectoryController.class)
class DirectoryControllerWebMvcTest {

    @Autowired MockMvc mockMvc;
    @MockBean DirectoryService directoryService;
    @MockBean UserSyncService userSyncService;

    @Test
    void unauthenticated_401() throws Exception {
        mockMvc.perform(get("/api/v1/directory/users").param("q", "al"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void contributor_ok() throws Exception {
        UserEntity u = new UserEntity();
        u.setId(UUID.randomUUID());
        when(userSyncService.syncFromJwt(any())).thenReturn(u);
        when(directoryService.searchUsers(eq(u.getId()), eq("al"), isNull()))
                .thenReturn(new DirectoryUsersResponse(List.of(
                        new DirectoryUser(u.getId(), "Alice", "a@ex.com", "A"))));

        mockMvc.perform(get("/api/v1/directory/users").param("q", "al")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_contributeur"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users[0].displayName").value("Alice"))
                .andExpect(jsonPath("$.users[0].email").value("a@ex.com"))
                .andExpect(jsonPath("$.users[0].initials").value("A"));
    }

    @Test
    void qTooShort_400() throws Exception {
        UserEntity u = new UserEntity();
        u.setId(UUID.randomUUID());
        when(userSyncService.syncFromJwt(any())).thenReturn(u);
        when(directoryService.searchUsers(any(), eq("a"), isNull()))
                .thenThrow(new ResponseStatusException(BAD_REQUEST, "q obligatoire"));

        mockMvc.perform(get("/api/v1/directory/users").param("q", "a")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_contributeur"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rateLimited_429() throws Exception {
        UserEntity u = new UserEntity();
        u.setId(UUID.randomUUID());
        when(userSyncService.syncFromJwt(any())).thenReturn(u);
        when(directoryService.searchUsers(any(), eq("al"), isNull()))
                .thenThrow(new ResponseStatusException(TOO_MANY_REQUESTS, "Limite"));

        mockMvc.perform(get("/api/v1/directory/users").param("q", "al")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_contributeur"))))
                .andExpect(status().isTooManyRequests());
    }
}
