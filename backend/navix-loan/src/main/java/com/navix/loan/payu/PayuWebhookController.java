package com.navix.loan.payu;

import com.navix.common.web.ApiResponse;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PayU's server webhook and the browser return. Both are unauthenticated at the filter chain (PayU has no
 * session), always answer 200, and only ever trigger a re-read from PayU: nothing in the body is trusted.
 */
@RestController
@RequestMapping("/api/webhooks/payu")
@RequiredArgsConstructor
public class PayuWebhookController {

    private final PayuService payu;

    @PostMapping(value = "/payment", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> payment(@RequestParam Map<String, String> fields) {
        payu.onCallback(fields);
        return ResponseEntity.ok().build();
    }

    /** The BFF forwards the browser's cross-site POST here and redirects to /repay?order=txnid. */
    @PostMapping(value = "/return", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ApiResponse<Map<String, String>> returned(@RequestParam Map<String, String> fields) {
        Map<String, String> out = new HashMap<>();
        out.put("txnid", payu.onCallback(fields));
        return ApiResponse.ok(out);
    }
}
