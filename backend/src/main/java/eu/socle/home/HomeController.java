// SPDX-License-Identifier: AGPL-3.0-or-later
package eu.socle.home;

import eu.socle.home.HomeDtos.HomeResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/home")
public class HomeController {

    private final HomeService homeService;

    public HomeController(HomeService homeService) {
        this.homeService = homeService;
    }

    @GetMapping
    public HomeResponse home(@AuthenticationPrincipal Jwt jwt) {
        return homeService.getHome(jwt);
    }
}
