package com.navix.loan.payu;

import com.navix.common.web.ApiResponse;
import com.navix.loan.payu.PayuReviewService.ReviewView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Staff review queue for PayU orders; literal path under /api/loan, beside pending-repayments. ACCOUNTANT/ADMIN. */
@RestController
@RequestMapping("/api/loan/payu-review")
@RequiredArgsConstructor
public class PayuReviewController {

    public record ResolveRequest(String note) {
    }

    private final PayuReviewService review;

    @GetMapping
    public ApiResponse<List<ReviewView>> list() {
        return ApiResponse.ok(review.listUnresolved());
    }

    @PostMapping("/{orderId}/resolve")
    public ApiResponse<Void> resolve(@PathVariable Long orderId, @RequestBody ResolveRequest request) {
        review.resolve(orderId, request.note());
        return ApiResponse.ok(null);
    }
}
