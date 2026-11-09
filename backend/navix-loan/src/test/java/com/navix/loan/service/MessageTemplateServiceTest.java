package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.whatsapp.WhatsAppGateway;
import com.navix.common.whatsapp.WhatsAppGateway.TemplateInfo;
import com.navix.loan.dto.MessagingDtos.StoredTemplateRequest;
import com.navix.loan.entity.MessageTemplate;
import com.navix.loan.repository.MessageTemplateRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageTemplateServiceTest {

    private static final String DLT = "1777179420408031626";
    private static final String BODY = "Dear {#var#}, payment of {#var#} is pending. - DhanBoost";

    @Mock private MessageTemplateRepository repository;
    @Mock private FeatureFlagService flags;
    @Mock private WhatsAppGateway whatsApp;

    private MessageTemplateService service;

    @BeforeEach
    void setUp() {
        service = new MessageTemplateService(repository, flags, whatsApp);
        ActorContext.set(new CurrentActor("1", "Admin A", "ADMIN"));
        when(flags.isEnabled(CustomerMessageService.FLAG, false)).thenReturn(true);
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    private static StoredTemplateRequest sms(String status, String dlt, String body, List<String> vars) {
        return new StoredTemplateRequest("SMS", "T1", null, body, "SERVICE", status, dlt, null, vars, "LIVE");
    }

    private static MessageTemplate row(String channel, String status, String provider, String body) {
        MessageTemplate t = new MessageTemplate();
        t.setId(5L);
        t.setChannel(channel);
        t.setName("t1");
        t.setCategory("SERVICE");
        t.setStatus(status);
        t.setProviderId(provider);
        t.setBody(body);
        return t;
    }

    private void assertRefused(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(code));
    }

    @Test
    void dsaAndOtherStaffAreRefusedOnEverySurface() {
        for (String role : List.of("DSA", "CREDIT_HEAD", "BORROWER")) {
            ActorContext.set(new CurrentActor("2", "X", role));
            assertRefused(() -> service.list("SMS"), "FORBIDDEN_ROLE");
            assertRefused(() -> service.create(sms("DRAFT", null, BODY, List.of("name", "amount"))), "FORBIDDEN_ROLE");
            assertRefused(() -> service.update(5L, sms("DRAFT", null, BODY, List.of("name", "amount"))), "FORBIDDEN_ROLE");
            assertRefused(service::refreshWhatsApp, "FORBIDDEN_ROLE");
        }
        verify(repository, never()).save(any());
    }

    @Test
    void writesAndRefreshRefuseWhileTheFlagIsOff() {
        when(flags.isEnabled(CustomerMessageService.FLAG, false)).thenReturn(false);
        assertRefused(() -> service.create(sms("DRAFT", null, BODY, List.of("name", "amount"))), "FEATURE_DISABLED");
        assertRefused(service::refreshWhatsApp, "FEATURE_DISABLED");
        verify(whatsApp, never()).listTemplates();
    }

    @Test
    void activeSmsNeedsWordingAndNineteenDigitDltId() {
        assertRefused(() -> service.create(sms("ACTIVE", null, BODY, List.of("name", "amount"))), "INVALID_STATUS");
        assertRefused(() -> service.create(sms("ACTIVE", DLT, null, List.of())), "INVALID_STATUS");
        assertRefused(() -> service.create(sms("ACTIVE", "1234", BODY, List.of("name", "amount"))), "INVALID_DLT_ID");
    }

    @Test
    void placeholderCountMustMatchTheChosenVariables() {
        assertRefused(() -> service.create(sms("DRAFT", null, BODY, List.of("name"))), "INVALID_TEMPLATE");
        assertRefused(() -> service.create(sms("DRAFT", null, BODY, List.of("name", "nonsense"))), "INVALID_TEMPLATE");
    }

    @Test
    void smsWordingRefusesTheRupeeSign() {
        assertRefused(() -> service.create(sms("DRAFT", null, "Pay ₹{#var#} now", List.of("amount"))), "INVALID_TEMPLATE");
    }

    @Test
    void ruleIsTightenedToWhatTheVariablesNeed() {
        var req = new StoredTemplateRequest("EMAIL", "e", "Hi", "Amount {{1}} due. Thanks", "SERVICE", null, null, null,
                List.of("amount"), "ANY");
        assertThat(service.create(req).rule()).isEqualTo("LIVE");
        var overdue = new StoredTemplateRequest("EMAIL", "e2", "Hi", "Late {{1}} days. Thanks", "SERVICE", null, null, null,
                List.of("daysOverdue"), "ANY");
        assertThat(service.create(overdue).rule()).isEqualTo("OVERDUE");
    }

    @Test
    void emailIsActiveOnSaveAndNeedsNoProviderId() {
        var req = new StoredTemplateRequest("EMAIL", "e", "Hello {{1}}", "Your loan. Thanks", "MARKETING", "DRAFT", null, null,
                List.of("name"), "ANY");
        var saved = service.create(req);
        assertThat(saved.status()).isEqualTo("ACTIVE");
        assertThat(saved.rule()).isEqualTo("ANY");
        assertThat(saved.unsendableReason()).isNull();
    }

    @Test
    void whatsAppStartsDraftAndCannotBeApprovedByHand() {
        var req = new StoredTemplateRequest("WHATSAPP", "db_new", null, "Dear {{1}}, thanks.", "SERVICE", "ACTIVE",
                "fake_name", null, List.of("name"), "ANY");
        var saved = service.create(req);
        assertThat(saved.status()).isEqualTo("DRAFT");
        assertThat(saved.providerId()).isNull();
    }

    @Test
    void whatsAppWordingIsCheckedAgainstThePortalRules() {
        var quoted = new StoredTemplateRequest("WHATSAPP", "db_new", null, "Say \"hi\" {{1}} ok", "SERVICE", null, null,
                null, List.of("name"), "ANY");
        assertRefused(() -> service.create(quoted), "INVALID_TEMPLATE");
        var edge = new StoredTemplateRequest("WHATSAPP", "db_new", null, "{{1}} is your name", "SERVICE", null, null, null,
                List.of("name"), "ANY");
        assertRefused(() -> service.create(edge), "INVALID_TEMPLATE");
    }

    @Test
    void activeWordingIsLockedButABlankSeedCanBeFilled() {
        MessageTemplate active = row("SMS", "ACTIVE", DLT, "Old {#var#}");
        active.setVariables("[\"name\"]");
        when(repository.findById(5L)).thenReturn(Optional.of(active));
        assertRefused(() -> service.update(5L, sms("ACTIVE", DLT, "New {#var#}", List.of("name"))), "TEMPLATE_LOCKED");

        MessageTemplate blank = row("SMS", "ACTIVE", DLT, null);
        when(repository.findById(5L)).thenReturn(Optional.of(blank));
        assertThat(service.update(5L, sms("ACTIVE", DLT, "New {#var#}", List.of("name"))).body()).isEqualTo("New {#var#}");
    }

    @Test
    void unsendableReasonsCoverEveryBlocker() {
        assertThat(MessageTemplateService.unsendableReason(row("SMS", "ACTIVE", DLT, "x"))).isNull();
        assertThat(MessageTemplateService.unsendableReason(row("SMS", "SUBMITTED", DLT, "x"))).contains("Active");
        assertThat(MessageTemplateService.unsendableReason(row("SMS", "ACTIVE", null, "x"))).contains("DLT");
        assertThat(MessageTemplateService.unsendableReason(row("SMS", "ACTIVE", DLT, null))).contains("wording");
        assertThat(MessageTemplateService.unsendableReason(row("WHATSAPP", "ACTIVE", null, "x"))).contains("SmartChat");
        MessageTemplate system = row("SMS", "ACTIVE", DLT, "x");
        system.setSystemManaged(true);
        assertThat(MessageTemplateService.unsendableReason(system)).contains("own flow");
    }

    @Test
    void refreshReadsTheRealStoredNameAndStatus() {
        MessageTemplate suffixed = row("WHATSAPP", "DRAFT", null, "x");
        suffixed.setName("db_offer");
        MessageTemplate rejected = row("WHATSAPP", "SUBMITTED", null, "x");
        rejected.setName("db_rej");
        MessageTemplate missing = row("WHATSAPP", "DRAFT", null, "x");
        missing.setName("db_gone");
        when(repository.findByChannelOrderByIdDesc("WHATSAPP")).thenReturn(List.of(suffixed, rejected, missing));
        when(whatsApp.listTemplates()).thenReturn(List.of(
                new TemplateInfo("db_offer_164", "APPROVED", null),
                new TemplateInfo("db_rej", "REJECTED", "Variable at start")));

        var result = service.refreshWhatsApp();

        assertThat(result.updated()).isEqualTo(2);
        assertThat(result.notFound()).containsExactly("db_gone");
        assertThat(suffixed.getStatus()).isEqualTo("ACTIVE");
        assertThat(suffixed.getProviderId()).isEqualTo("db_offer_164");
        assertThat(rejected.getStatus()).isEqualTo("REJECTED");
        assertThat(rejected.getRejectionReason()).isEqualTo("Variable at start");
        assertThat(missing.getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void anAmbiguousSuffixMatchesNothing() {
        List<TemplateInfo> remote = List.of(new TemplateInfo("a_164", "APPROVED", null), new TemplateInfo("a_165", "APPROVED", null));
        assertThat(MessageTemplateService.match("a", remote)).isNull();
        assertThat(MessageTemplateService.match("a_16", List.of(new TemplateInfo("a_164", "APPROVED", null)))).isNull();
    }

    @Test
    void renderFillsDltPlaceholdersInOrderAndNumberedOnesByNumber() {
        assertThat(MessageTemplateService.render("Dear {#var#}, pay {#var#}.", List.of("Asha", "Rs. 10")))
                .isEqualTo("Dear Asha, pay Rs. 10.");
        assertThat(MessageTemplateService.render("{{2}} then {{1}} and {{2}}", List.of("a$", "b")))
                .isEqualTo("b then a$ and b");
    }

    @Test
    void adaptTurnsTypedVariablesIntoSingleRecipientInputs() {
        MessageTemplate t = row("SMS", "ACTIVE", DLT, "x {#var#} {#var#}");
        t.setVariables("[\"name\",\"in:date:Promise date\"]");
        t.setRule("ANY");
        var adapted = MessageTemplateService.adapt(t);
        assertThat(adapted.key()).isEqualTo("tpl:5");
        assertThat(adapted.params()).containsExactly("name", "in:v2");
        assertThat(adapted.inputs().get(0).label()).isEqualTo("Promise date");
        assertThat(adapted.inputs().get(0).type()).isEqualTo("DATE");
        assertThat(adapted.singleOnly()).isTrue();
        assertThat(adapted.provider()).isEqualTo(DLT);
    }

    @Test
    void unknownKeyIsRefused() {
        assertRefused(() -> service.requireSendable("tpl:abc"), "INVALID_TEMPLATE");
        assertRefused(() -> service.requireSendable("tpl:999"), "INVALID_TEMPLATE");
    }
}
