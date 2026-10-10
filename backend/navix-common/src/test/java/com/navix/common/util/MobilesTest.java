package com.navix.common.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MobilesTest {
    @Test
    void normalizes() {
        assertEquals("9876543210", Mobiles.normalize("+91 98765-43210"));
        assertEquals("9876543210", Mobiles.normalize("09876543210"));
        assertNull(Mobiles.normalize(" "));
        assertNull(Mobiles.normalize(null));
    }
}
