package com.navix.loan.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.loan.service.BureauMobiles.Phone;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BureauMobilesTest {
    private final ObjectMapper om = new ObjectMapper();

    private List<Phone> run(String json, Set<String> reg) throws Exception {
        return BureauMobiles.extract(om.readTree(json), reg);
    }

    @Test
    void crifPrefixesCollapseKeepingNewestAndFlagRegistered() throws Exception {
        String j = """
            {"canonical":{"data":{"credit_report":{"REQUEST":{"PHONE-1":"9000000001"},
             "PERSONAL-INFO-VARIATION":{"PHONE-NUMBER-VARIATIONS":{"VARIATION":[
               {"VALUE":"09000000001","REPORTED-DATE":"15-09-2025"},
               {"VALUE":"+919000000001","REPORTED-DATE":"30-06-2026"},
               {"VALUE":"0221234567","REPORTED-DATE":"01-01-2026"}]}}}}}}""";
        List<Phone> p = run(j, Set.of("9000000001"));
        assertEquals(2, p.size());
        assertEquals("MOBILE", p.get(0).kind());
        assertEquals(LocalDate.of(2026, 6, 30), p.get(0).reportedDate());
        assertTrue(p.get(0).registered());
        assertEquals("CRIF", p.get(0).source());
        assertEquals("OTHER", p.get(1).kind());
        assertNull(p.get(1).normalized());
    }

    @Test
    void crifEmptyStringAndSingleObject() throws Exception {
        assertTrue(run("{\"data\":{\"PERSONAL-INFO-VARIATION\":{\"PHONE-NUMBER-VARIATIONS\":\"\"}}}", Set.of())
                .isEmpty());
        List<Phone> p = run("{\"data\":{\"PERSONAL-INFO-VARIATION\":{\"PHONE-NUMBER-VARIATIONS\":{\"VARIATION\":"
                + "{\"VALUE\":\"9111111111\",\"REPORTED-DATE\":\"31-07-2026\"}}}}}", Set.of());
        assertEquals(1, p.size());
        assertFalse(p.get(0).registered());
    }

    @Test
    void experianDotZeroMaskedLandlineObjectAndArray() throws Exception {
        String j = """
            {"data":{"jsonExperianReport":{"Current_Application":{"Current_Application_Details":
               {"Current_Applicant_Details":{"MobilePhoneNumber":"9222222222.0"}}},
             "CAIS_Account":{"CAIS_Account_DETAILS":[
               {"Subscriber_Name":"BANK A","Date_Reported":"20260810",
                "CAIS_Holder_Phone_Details":{"Mobile_Telephone_Number":"98XXXXXX10"}},
               {"Subscriber_Name":"BANK B","Date_Reported":"20260701",
                "CAIS_Holder_Phone_Details":[{"Telephone_Number":"9333333333"},{"Telephone_Number":"1"},
                                             {"Telephone_Number":"0112345678"}]}]}}}}""";
        List<Phone> p = run(j, Set.of());
        assertEquals(4, p.size()); // "1" dropped
        assertEquals("9222222222", p.get(0).normalized());
        assertEquals("Application", p.get(0).context());
        assertEquals("MASKED", p.get(1).kind());
        assertEquals("BANK A", p.get(1).context());
        assertEquals("9333333333", p.get(2).normalized());
        assertEquals("OTHER", p.get(3).kind());
        assertEquals("EXPERIAN", p.get(2).source());
    }

    @Test
    void unknownShapeAndNullAreEmpty() throws Exception {
        assertTrue(run("{\"result\":{\"foo\":1}}", Set.of()).isEmpty());
        assertTrue(BureauMobiles.extract(null, null).isEmpty());
    }

    @Test
    void experianHolderKeepsBothMobileAndTelephoneFields() throws Exception {
        String j = "{\"data\":{\"jsonExperianReport\":{\"Current_Application\":{},\"CAIS_Account\":{"
                + "\"CAIS_Account_DETAILS\":{\"Subscriber_Name\":\"B\",\"Date_Reported\":\"20260810\","
                + "\"CAIS_Holder_Phone_Details\":{\"Mobile_Telephone_Number\":\"9444444444\","
                + "\"Telephone_Number\":\"9555555555\"}}}}}}";
        assertEquals(List.of("9444444444", "9555555555"),
                run(j, Set.of()).stream().map(Phone::normalized).toList());
        String same = j.replace("9555555555", "9444444444");
        assertEquals(1, run(same, Set.of()).size());
    }
}
