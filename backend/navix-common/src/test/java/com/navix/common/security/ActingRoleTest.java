package com.navix.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ActingRoleTest {

    @Test
    void allowedMatrix() {
        for (String r : new String[] {"CREDIT_HEAD", "CREDIT_EXECUTIVE", "DISBURSEMENT_HEAD", "ACCOUNTANT",
                "COLLECTION_HEAD", "COLLECTION_EXECUTIVE", "TELECALLER"}) {
            assertThat(ActingRole.allowed("ADMIN", r)).as("ADMIN as " + r).isTrue();
        }
        assertThat(ActingRole.allowed("CREDIT_HEAD", "CREDIT_EXECUTIVE")).isTrue();
        assertThat(ActingRole.allowed("COLLECTION_HEAD", "COLLECTION_EXECUTIVE")).isTrue();
        assertThat(ActingRole.allowed("CREDIT_HEAD", "ADMIN")).isFalse();
        assertThat(ActingRole.allowed("CREDIT_HEAD", "COLLECTION_EXECUTIVE")).isFalse();
        assertThat(ActingRole.allowed("CREDIT_EXECUTIVE", "CREDIT_HEAD")).isFalse();
        assertThat(ActingRole.allowed("ADMIN", "DSA")).isFalse();
        assertThat(ActingRole.allowed(null, "TELECALLER")).isFalse();
    }

    @Test
    void normalize() {
        assertThat(ActingRole.normalize("ADMIN", " credit_executive ")).isEqualTo("CREDIT_EXECUTIVE");
        assertThat(ActingRole.normalize("ADMIN", "ADMIN")).isNull();
        assertThat(ActingRole.normalize("CREDIT_HEAD", "CREDIT_HEAD")).isNull();
        assertThat(ActingRole.normalize("ADMIN", "DSA")).isNull();
        assertThat(ActingRole.normalize("ADMIN", "NOPE")).isNull();
        assertThat(ActingRole.normalize("ADMIN", "  ")).isNull();
        assertThat(ActingRole.normalize("ADMIN", null)).isNull();
        assertThat(ActingRole.normalize("ACCOUNTANT", "ADMIN")).isNull();
    }
}
