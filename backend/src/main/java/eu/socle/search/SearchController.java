package eu.socle.search;

import eu.socle.search.SearchDtos.SearchResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public SearchResponse search(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "q", required = false, defaultValue = "") String q,
            @RequestParam(required = false) UUID spaceId,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String docType,
            @RequestParam(required = false) Integer limit
    ) {
        return searchService.search(jwt, q, spaceId, tag, docType, limit);
    }
}
