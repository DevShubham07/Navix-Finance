package com.navix.loan.esigndoc;

import com.lowagie.text.pdf.PdfReader;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.notification.event.EsignInviteEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.common.verification.EsignPort;
import com.navix.common.verification.EsignPort.ContractRequest;
import com.navix.common.verification.EsignPort.ContractSession;
import com.navix.common.verification.EsignPort.ContractSignerSpec;
import com.navix.common.verification.EsignPort.ContractStatus;
import com.navix.loan.esigndoc.EsignDocumentDtos.CreateRequest;
import com.navix.loan.esigndoc.EsignDocumentDtos.Detail;
import com.navix.loan.esigndoc.EsignDocumentDtos.SignerInput;
import com.navix.loan.esigndoc.EsignDocumentDtos.SignerView;
import com.navix.loan.esigndoc.EsignDocumentDtos.Summary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin "eSign documents": upload a PDF, name up to five signers (external or the admin), and have
 * them Aadhaar-eSign it as ONE Signzy contract. ADMIN-only and flag-gated: every contract is real,
 * billable and legally binding, with no sandbox.
 *
 * <p>Signzy is the authority on signing state; the local rows are a cache refreshed by
 * {@link #refresh} (the admin) or {@link #onCallback} (the provider webhook, which carries nothing
 * we trust, only the contract id to re-read).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsignDocumentService {

    public static final String FLAG = "esign-documents";
    static final String KEY_PREFIX = "esign-documents/";
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_SIGNERS = 5;
    private static final Set<String> POSITIONS = Set.of("TopLeft", "TopCenter", "TopRight", "MiddleLeft",
            "MiddleCenter", "MiddleRight", "BottomLeft", "BottomCenter", "BottomRight");

    private final EsignDocumentRepository documents;
    private final EsignDocumentSignerRepository signers;
    private final EsignPort esign;
    private final DocumentStoragePort storage;
    private final FeatureFlagService flags;
    private final ApplicationEventPublisher events;

    /** Where the signer lands after Signzy; a public page, since external signers have no staff session. */
    @Value("${navix.app.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    // ---- reads --------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Summary> list() {
        requireAdmin();
        List<EsignDocument> docs = documents.findAllByOrderByCreatedAtDescIdDesc();
        if (docs.isEmpty()) {
            return List.of();
        }
        Map<Long, List<EsignDocumentSigner>> byDoc = signers
                .findByDocumentIdIn(docs.stream().map(EsignDocument::getId).toList()).stream()
                .collect(Collectors.groupingBy(EsignDocumentSigner::getDocumentId));
        return docs.stream().map(d -> {
            List<EsignDocumentSigner> s = byDoc.getOrDefault(d.getId(), List.of());
            return new Summary(d.getId(), d.getTitle(), d.getStatus(), s.size(),
                    (int) s.stream().filter(x -> "SIGNED".equals(x.getStatus())).count(), d.getCreatedAt());
        }).toList();
    }

    @Transactional(readOnly = true)
    public Detail detail(Long id) {
        requireAdmin();
        return toDetail(load(id));
    }

    // ---- create -------------------------------------------------------------

    @Transactional
    public Detail create(CreateRequest req) {
        CurrentActor actor = requireAdmin();
        requireEnabled();
        validateShape(req);

        byte[] bytes;
        try {
            bytes = storage.fetch(req.objectKey());
        } catch (RuntimeException e) {
            throw invalid("The uploaded file could not be read. Upload it again.");
        }
        if (bytes.length > MAX_BYTES) {
            throw invalid("The PDF is larger than 10 MB.");
        }
        int pageCount = pageCount(bytes);

        List<List<String>> resolved = new ArrayList<>();
        for (SignerInput s : req.signers()) {
            resolved.add(resolvePages(s.pages(), pageCount, s.name()));
        }

        EsignDocument doc = new EsignDocument();
        doc.setTitle(req.title().trim());
        doc.setFileName(req.fileName().trim());
        doc.setS3ObjectKey(req.objectKey());
        doc.setPageCount(pageCount);
        doc.setCreatedBy(Long.parseLong(actor.id()));
        doc.setStatus("SENT");

        List<EsignDocumentSigner> rows = new ArrayList<>();
        for (int i = 0; i < req.signers().size(); i++) {
            SignerInput in = req.signers().get(i);
            EsignDocumentSigner row = new EsignDocumentSigner();
            row.setSignerOrder(i + 1);
            row.setName(in.name().trim());
            row.setSelf(in.self());
            row.setEmail(in.self() ? null : blankToNull(in.email()));
            row.setPages(in.pages().trim());
            row.setPosition(in.position());
            rows.add(row);
        }

        ContractSession session = null;
        try {
            String base = baseUrl();
            session = esign.initiateContract(new ContractRequest(
                    storage.presignDownload(req.objectKey()), doc.getTitle(), null,
                    base + "/esign/done?status=ok", base + "/esign/done?status=failed",
                    java.util.stream.IntStream.range(0, rows.size())
                            .mapToObj(i -> new ContractSignerSpec(rows.get(i).getName(), resolved.get(i),
                                    rows.get(i).getPosition()))
                            .toList()));
            doc.setContractId(session.contractId());
        } catch (RuntimeException e) {
            // Keep the record: an admin must see that an attempt failed (and why), not lose it.
            log.warn("eSign document contract could not be opened: {}", e.toString());
            doc.setStatus("FAILED");
            doc.setErrorMessage(truncate("The signing provider refused the document: " + e.getMessage(), 1000));
        }
        doc = documents.save(doc);

        for (int i = 0; i < rows.size(); i++) {
            EsignDocumentSigner row = rows.get(i);
            row.setDocumentId(doc.getId());
            if (session != null) {
                row.setSignerId(session.signers().get(i).signerId());
                row.setSignUrl(session.signers().get(i).signUrl());
            }
        }
        signers.saveAll(rows);

        if (session != null && req.sendEmail()) {
            for (EsignDocumentSigner row : rows) {
                if (!row.isSelf() && row.getEmail() != null && row.getSignUrl() != null) {
                    sendInvite(doc, row);
                }
            }
        }
        return toDetail(doc);
    }

    // ---- refresh / callback / resend ------------------------------------------

    @Transactional
    public Detail refresh(Long id) {
        requireAdmin();
        requireEnabled();
        EsignDocument doc = load(id);
        if (doc.getContractId() != null && "SENT".equals(doc.getStatus())) {
            try {
                sync(doc);
            } catch (RuntimeException e) {
                throw new BusinessException("ESIGN_PROVIDER_FAILED",
                        "Could not read the signing status from the provider. Try again shortly.");
            }
        }
        return toDetail(doc);
    }

    /**
     * Provider callback for a contract that is not a borrower sanction. Re-reads the contract; never
     * trusts the callback body. No actor: called unauthenticated from the webhook controller.
     *
     * @return true when the contract belongs to an admin document (even if the re-read failed)
     */
    @Transactional
    public boolean onCallback(String contractId) {
        if (contractId == null || contractId.isBlank()) {
            return false;
        }
        EsignDocument doc = documents.findFirstByContractId(contractId).orElse(null);
        if (doc == null) {
            return false;
        }
        if ("SENT".equals(doc.getStatus())) {
            try {
                sync(doc);
            } catch (RuntimeException e) {
                log.warn("eSign document {} callback could not be resolved: {}", doc.getId(), e.toString());
            }
        }
        return true;
    }

    @Transactional
    public Detail resend(Long id, Long signerRowId) {
        requireAdmin();
        requireEnabled();
        EsignDocument doc = load(id);
        EsignDocumentSigner row = signers.findById(signerRowId)
                .filter(s -> s.getDocumentId().equals(id))
                .orElseThrow(() -> new ResourceNotFoundException("EsignDocumentSigner", String.valueOf(signerRowId)));
        if (row.isSelf() || row.getEmail() == null) {
            throw invalid("This signer has no email address to send to.");
        }
        if (!"SENT".equals(doc.getStatus()) || !"PENDING".equals(row.getStatus()) || row.getSignUrl() == null) {
            throw invalid("This signer can no longer be sent a signing link.");
        }
        sendInvite(doc, row);
        return toDetail(doc);
    }

    // ---- internals ----------------------------------------------------------

    /** Apply the provider's current state to the rows. Throws on a provider/transport failure. */
    private void sync(EsignDocument doc) {
        ContractStatus st = esign.fetchContract(doc.getContractId());
        List<EsignDocumentSigner> rows = signers.findByDocumentIdOrderBySignerOrder(doc.getId());
        if (st.expired()) {
            doc.setStatus("EXPIRED");
            return;
        }
        Instant now = Instant.now();
        for (EsignDocumentSigner row : rows) {
            st.signers().stream().filter(s -> s.signerId() != null && s.signerId().equals(row.getSignerId()))
                    .findFirst().ifPresent(s -> {
                        if ("SIGNED".equals(s.status()) && !"SIGNED".equals(row.getStatus())) {
                            row.setSignedAt(now);
                        }
                        row.setStatus(s.status());
                        row.setErrorMessage(truncate(s.error(), 1000));
                    });
        }
        if (st.completed()) {
            for (EsignDocumentSigner row : rows) {
                if (!"SIGNED".equals(row.getStatus())) {
                    row.setStatus("SIGNED");
                    row.setErrorMessage(null);
                    row.setSignedAt(now);
                }
            }
            if (st.signedPdf() != null) {
                String key = KEY_PREFIX + doc.getId() + "/signed.pdf";
                storage.store(key, st.signedPdf(), "application/pdf");
                doc.setSignedS3ObjectKey(key);
            }
            doc.setAuditCertificateUrl(st.auditCertificateUrl());
            doc.setStatus("COMPLETED");
            doc.setCompletedAt(now);
        }
    }

    private void sendInvite(EsignDocument doc, EsignDocumentSigner row) {
        events.publishEvent(new EsignInviteEvent(row.getId(), doc.getTitle(), row.getName(),
                row.getEmail(), row.getSignUrl()));
    }

    private Detail toDetail(EsignDocument d) {
        List<SignerView> views = signers.findByDocumentIdOrderBySignerOrder(d.getId()).stream()
                .map(s -> new SignerView(s.getId(), s.getSignerOrder(), s.getName(), s.getEmail(), s.isSelf(),
                        s.getPages(), s.getPosition(), s.getStatus(), s.getSignUrl(), s.getErrorMessage(),
                        s.getSignedAt(), s.getEmailStatus(), s.getEmailSentAt()))
                .toList();
        return new Detail(d.getId(), d.getTitle(), d.getFileName(), d.getPageCount(), d.getStatus(),
                d.getContractId(), d.getErrorMessage(), d.getCreatedAt(), d.getCompletedAt(),
                storage.presignDownload(d.getS3ObjectKey()),
                d.getSignedS3ObjectKey() == null ? null : storage.presignDownload(d.getSignedS3ObjectKey()),
                d.getAuditCertificateUrl(), views);
    }

    private EsignDocument load(Long id) {
        return documents.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("EsignDocument", String.valueOf(id)));
    }

    private void validateShape(CreateRequest req) {
        if (req == null || isBlank(req.title()) || isBlank(req.fileName()) || isBlank(req.objectKey())) {
            throw invalid("Title and PDF are required.");
        }
        if (req.title().trim().length() > 200) {
            throw invalid("The title is too long (200 characters at most).");
        }
        if (!req.objectKey().startsWith(KEY_PREFIX) || req.objectKey().contains("..")) {
            throw invalid("The PDF must be uploaded through the eSign document upload.");
        }
        List<SignerInput> list = req.signers();
        if (list == null || list.isEmpty() || list.size() > MAX_SIGNERS) {
            throw invalid("Add between 1 and " + MAX_SIGNERS + " signers.");
        }
        for (SignerInput s : list) {
            if (isBlank(s.name())) {
                throw invalid("Every signer needs a name (as on their Aadhaar).");
            }
            if (isBlank(s.pages())) {
                throw invalid("Choose the pages for " + s.name() + ".");
            }
            if (s.position() == null || !POSITIONS.contains(s.position())) {
                throw invalid("Choose a valid signature position for " + s.name() + ".");
            }
            if (req.sendEmail() && !s.self() && (isBlank(s.email()) || !s.email().contains("@"))) {
                throw invalid("A valid email is required for " + s.name() + " to send the signing link.");
            }
        }
    }

    /** All | First | Last | "1,3"  ->  the page list Signzy wants (["All"] or explicit numbers). */
    static List<String> resolvePages(String raw, int pageCount, String who) {
        String p = raw.trim();
        if (p.equalsIgnoreCase("All")) {
            return List.of("All");
        }
        if (p.equalsIgnoreCase("First")) {
            return List.of("1");
        }
        if (p.equalsIgnoreCase("Last")) {
            return List.of(String.valueOf(pageCount));
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : p.split(",")) {
            int n;
            try {
                n = Integer.parseInt(part.trim());
            } catch (NumberFormatException e) {
                throw invalid("Pages for " + who + " must be All, First, Last or numbers like 1,3.");
            }
            if (n < 1 || n > pageCount) {
                throw invalid("Page " + n + " does not exist: the document has " + pageCount + " page(s).");
            }
            out.add(String.valueOf(n));
        }
        return List.copyOf(out);
    }

    private static int pageCount(byte[] bytes) {
        if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F') {
            throw invalid("The file is not a PDF.");
        }
        try {
            PdfReader reader = new PdfReader(bytes);
            try {
                int n = reader.getNumberOfPages();
                if (n < 1) {
                    throw invalid("The PDF has no pages.");
                }
                return n;
            } finally {
                reader.close();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("The PDF could not be read (it may be corrupt or password-protected).");
        }
    }

    private String baseUrl() {
        return frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1) : frontendBaseUrl;
    }

    private void requireEnabled() {
        if (!flags.isEnabled(FLAG, false)) {
            throw new BusinessException("FEATURE_DISABLED", "eSign documents are not enabled.");
        }
    }

    private static CurrentActor requireAdmin() {
        CurrentActor actor = ActorContext.get();
        if (actor == null || !"ADMIN".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "Only an ADMIN can manage eSign documents");
        }
        return actor;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("ESIGN_DOC_INVALID", message);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
