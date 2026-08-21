package com.navix.verification.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps a provider endpoint path to the {@code (operation, provider)} pair the Provider API dashboard
 * groups and filters on.
 *
 * <p>Deriving these centrally from the path keeps the 15 provider clients untouched — the
 * alternative was threading two extra arguments through every client call site. The operation names
 * are deliberately the SAME vocabulary the manual workbench catalog uses
 * ({@code PAN}, {@code EMAIL}, {@code ADDRESS}, {@code BUREAU}, {@code PENNY_DROP},
 * {@code FACE_MATCH}, {@code UAN}) so manual and live rows filter identically; the flows the
 * workbench cannot fire by hand ({@code DIGILOCKER}, {@code LIVENESS}, {@code ESIGN}) extend it.
 *
 * <p>An unknown path is not an error — it yields {@code OTHER} plus a provider inferred from the
 * path shape, so a newly added client still gets audited before anyone remembers to update this map.
 */
public final class ProviderCallCatalog {

    /** Signzy paths are all under {@code /api/v3/}; Digitap's are not. */
    private static final String SIGNZY_PREFIX = "/api/v3/";

    private record Route(String operation, String provider) {}

    private static final Map<String, Route> ROUTES = new LinkedHashMap<>();

    static {
        // Digitap
        ROUTES.put("/credit_analytics/request", new Route("BUREAU", "DIGITAP"));
        ROUTES.put("/validation/kyc/v1/pan_details_plus", new Route("PAN", "DIGITAP"));
        ROUTES.put("/cv/email_verification/v1", new Route("EMAIL", "DIGITAP"));
        ROUTES.put("/ent/v1/address-verification", new Route("ADDRESS", "DIGITAP"));
        ROUTES.put("/fmfl/v2/face-match", new Route("FACE_MATCH", "DIGITAP"));
        ROUTES.put("/cv/v3/uan_basic/sync", new Route("UAN", "DIGITAP"));
        // Signzy — the two bureaux stay distinguishable because the router tries Experian then CRIF.
        ROUTES.put("/api/v3/bureau/experian-lite", new Route("BUREAU", "SIGNZY_EXPERIAN"));
        ROUTES.put("/api/v3/bureau/crif", new Route("BUREAU", "SIGNZY_CRIF"));
        ROUTES.put("/api/v3/pan/compliance-206-individual-search", new Route("PAN", "SIGNZY"));
        ROUTES.put("/api/v3/email/verificationV2", new Route("EMAIL", "SIGNZY"));
        ROUTES.put("/api/v3/geocoding/reverse-geocode", new Route("ADDRESS", "SIGNZY"));
        ROUTES.put("/api/v3/bankaccountverification/pennydrop-v1", new Route("PENNY_DROP", "SIGNZY"));
        ROUTES.put("/api/v3/digilocker-v2/createUrl", new Route("DIGILOCKER", "SIGNZY"));
        ROUTES.put("/api/v3/digilocker-v2/geteAadhaar", new Route("DIGILOCKER", "SIGNZY"));
        ROUTES.put("/api/v3/liveness-secure/createUrl", new Route("LIVENESS", "SIGNZY"));
        ROUTES.put("/api/v3/liveness-secure/getData", new Route("LIVENESS", "SIGNZY"));
        ROUTES.put("/api/v3/contract/initiate", new Route("ESIGN", "SIGNZY"));
        ROUTES.put("/api/v3/contract/pullData", new Route("ESIGN", "SIGNZY"));
    }

    private ProviderCallCatalog() {
        // static holder - no instances
    }

    public static String operationFor(String endpoint) {
        Route route = ROUTES.get(strip(endpoint));
        return route != null ? route.operation() : "OTHER";
    }

    public static String providerFor(String endpoint) {
        String path = strip(endpoint);
        Route route = ROUTES.get(path);
        if (route != null) {
            return route.provider();
        }
        return path.startsWith(SIGNZY_PREFIX) ? "SIGNZY" : "DIGITAP";
    }

    /** Drop any query string so {@code /x?y=1} still resolves to the {@code /x} route. */
    private static String strip(String endpoint) {
        if (endpoint == null) {
            return "";
        }
        int query = endpoint.indexOf('?');
        return query < 0 ? endpoint : endpoint.substring(0, query);
    }
}
