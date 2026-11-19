package com.mtm.citybuilder.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CityBuilderCoreTest {
    @Test
    void modIdIsAValidNamespace() {
        assertTrue(CityBuilderCore.MOD_ID.matches("[a-z0-9_.-]+"));
    }
}
