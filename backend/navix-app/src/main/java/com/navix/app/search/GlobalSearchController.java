package com.navix.app.search;

import com.navix.app.search.GlobalSearchDtos.SearchResponse;
import com.navix.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The staff console's global search ({@code Cmd/Ctrl+K}).
 *
 * <p>Mounted under {@code /api/staff/**}, which {@code SecurityConfig} already gates on the token's
 * staff audience — so this needs no matcher of its own. The literal {@code search} segment outranks
 * {@code StaffController}'s {@code /api/staff/{id}} in Spring's path-pattern ordering, the same way
 * {@code /api/staff/me} already does.
 *
 * <p>Which groups run, how each is scoped, and the outright rejection of DSA all live in
 * {@link GlobalSearchService} — beside the services that enforce them.
 */
@RestController
@RequestMapping("/api/staff/search")
@RequiredArgsConstructor
public class GlobalSearchController {

    private final GlobalSearchService searchService;

    @GetMapping
    public ApiResponse<SearchResponse> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "5") int limit) {
        return ApiResponse.ok(searchService.search(q, limit));
    }
}
