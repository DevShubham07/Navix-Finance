package com.navix.app.dashboard;

import com.navix.app.dashboard.DashboardDtos.Aum;
import com.navix.app.dashboard.DashboardDtos.Calendar;
import com.navix.app.dashboard.DashboardDtos.Allocation;
import com.navix.app.dashboard.DashboardDtos.CollectionAnalysis;
import com.navix.app.dashboard.DashboardDtos.Companies;
import com.navix.app.dashboard.DashboardDtos.Daily;
import com.navix.app.dashboard.DashboardDtos.Geo;
import com.navix.app.dashboard.DashboardDtos.Leaderboard;
import com.navix.app.dashboard.DashboardDtos.Monthly;
import com.navix.app.dashboard.DashboardDtos.PreclosureWeek;
import com.navix.app.dashboard.DashboardDtos.Records;
import com.navix.app.dashboard.DashboardDtos.RoleView;
import com.navix.app.dashboard.DashboardDtos.Snapshot;
import com.navix.app.dashboard.DashboardDtos.TargetRow;
import com.navix.app.dashboard.DashboardDtos.TargetUpdate;
import com.navix.app.dashboard.DashboardDtos.Team;
import com.navix.common.exception.BusinessException;
import com.navix.common.web.ApiResponse;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff dashboard analytics. Every GET takes the dashboard-local {@code view}, a period and an optional
 * {@code staffIds} filter; {@link DashboardScope} validates all three server-side, and each endpoint
 * names the views allowed to read it. DSA is rejected everywhere (it satisfies the staff audience gate).
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService service;

    @GetMapping("/snapshot")
    public ApiResponse<Snapshot> snapshot(@RequestParam String view, @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to,
                                          @RequestParam(required = false) String staffIds) {
        LocalDate[] p = DashboardService.period(from, to);
        return ApiResponse.ok(service.snapshot(scope(view, staffIds), p[0], p[1]));
    }

    @GetMapping("/monthly")
    public ApiResponse<Monthly> monthly(@RequestParam String view, @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false) String staffIds,
                                        @RequestParam(defaultValue = "4") int months) {
        LocalDate[] p = DashboardService.period(from, to);
        return ApiResponse.ok(service.monthly(scope(view, staffIds), p[0], p[1], months));
    }

    @GetMapping("/daily")
    public ApiResponse<Daily> daily(@RequestParam String view, @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(required = false) String staffIds) {
        LocalDate[] p = DashboardService.period(from, to);
        return ApiResponse.ok(service.daily(scope(view, staffIds), p[0], p[1]));
    }

    @GetMapping("/preclosure-week")
    public ApiResponse<PreclosureWeek> preclosureWeek(@RequestParam String view,
                                                      @RequestParam(required = false) String date,
                                                      @RequestParam(required = false) String staffIds) {
        return ApiResponse.ok(service.preclosureWeek(scope(view, staffIds), DashboardService.parseDate(date, null)));
    }

    @GetMapping("/aum")
    public ApiResponse<Aum> aum(@RequestParam String view, @RequestParam(required = false) String asOf,
                                @RequestParam(required = false) String staffIds) {
        return ApiResponse.ok(service.aum(scope(view, staffIds), DashboardService.parseDate(asOf, null)));
    }

    @GetMapping("/collection-analysis")
    public ApiResponse<CollectionAnalysis> collectionAnalysis(@RequestParam String view,
                                                              @RequestParam(required = false) String date,
                                                              @RequestParam(required = false) String groupBy,
                                                              @RequestParam(required = false) String staffIds) {
        return ApiResponse.ok(service.collectionAnalysis(scope(view, staffIds),
                DashboardService.parseDate(date, null), groupBy));
    }

    @GetMapping("/team")
    public ApiResponse<Team> team(@RequestParam String view, @RequestParam(required = false) String from,
                                  @RequestParam(required = false) String to,
                                  @RequestParam(required = false) String staffIds) {
        LocalDate[] p = DashboardService.period(from, to);
        return ApiResponse.ok(service.team(scope(view, staffIds), p[0], p[1]));
    }

    @GetMapping("/calendar")
    public ApiResponse<Calendar> calendar(@RequestParam String view, @RequestParam(required = false) String month,
                                          @RequestParam(required = false) String mode,
                                          @RequestParam(required = false) String staffIds) {
        return ApiResponse.ok(service.calendar(scope(view, staffIds), month, mode));
    }

    @GetMapping("/geo")
    public ApiResponse<Geo> geo(@RequestParam String view, @RequestParam(required = false) String from,
                                @RequestParam(required = false) String to,
                                @RequestParam(required = false) String staffIds) {
        LocalDate[] p = DashboardService.period(from, to);
        return ApiResponse.ok(service.geo(scope(view, staffIds), p[0], p[1]));
    }

    @GetMapping("/companies")
    public ApiResponse<Companies> companies(@RequestParam String view, @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to,
                                            @RequestParam(required = false) String staffIds) {
        LocalDate[] p = DashboardService.period(from, to);
        return ApiResponse.ok(service.companies(scope(view, staffIds), p[0], p[1]));
    }

    @GetMapping("/collection-allocation")
    public ApiResponse<Allocation> collectionAllocation(@RequestParam String view,
                                                        @RequestParam(required = false) String from,
                                                        @RequestParam(required = false) String to,
                                                        @RequestParam(required = false) String staffIds) {
        LocalDate[] p = DashboardService.period(from, to);
        return ApiResponse.ok(service.allocation(scope(view, staffIds), p[0], p[1]));
    }

    @GetMapping("/leaderboard")
    public ApiResponse<Leaderboard> leaderboard(@RequestParam String view, @RequestParam String board,
                                                @RequestParam(required = false) String from,
                                                @RequestParam(required = false) String to,
                                                @RequestParam(required = false) String staffIds) {
        LocalDate[] p = DashboardService.period(from, to);
        return ApiResponse.ok(service.leaderboard(scope(view, staffIds), board, p[0], p[1]));
    }

    @GetMapping("/role-view")
    public ApiResponse<RoleView> roleView(@RequestParam String view, @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to,
                                          @RequestParam(required = false) String staffIds) {
        LocalDate[] p = DashboardService.period(from, to);
        return ApiResponse.ok(service.roleView(scope(view, staffIds), p[0], p[1]));
    }

    @GetMapping("/records")
    public ApiResponse<Records> records(@RequestParam String view, @RequestParam String metric,
                                        @RequestParam(required = false) String key,
                                        @RequestParam(defaultValue = "ALL") String segment,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false) String staffIds) {
        LocalDate[] p = DashboardService.period(from, to);
        return ApiResponse.ok(service.records(scope(view, staffIds), metric, key, segment, q, page, size, p[0], p[1]));
    }

    @GetMapping("/targets")
    public ApiResponse<List<TargetRow>> targets(@RequestParam(required = false) String from,
                                                @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.targets(from, to));
    }

    @PutMapping("/targets/{month}")
    public ApiResponse<TargetRow> saveTarget(@PathVariable String month, @RequestBody TargetUpdate body) {
        return ApiResponse.ok(service.saveTarget(month, body));
    }

    private DashboardScope scope(String view, String staffIdsCsv) {
        return service.scope(view, parseIds(staffIdsCsv));
    }

    private static List<Long> parseIds(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        try {
            return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Long::valueOf).toList();
        } catch (NumberFormatException e) {
            throw new BusinessException("INVALID_STAFF_IDS", "staffIds must be a comma-separated list of ids");
        }
    }
}
