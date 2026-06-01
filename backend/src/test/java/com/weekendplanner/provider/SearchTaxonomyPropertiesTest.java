package com.weekendplanner.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchTaxonomyPropertiesTest {

    private final SearchTaxonomyProperties taxonomy = new SearchTaxonomyProperties();

    @Test
    void exposesDefaultTypeCodesAndTagsForCoreCategories() {
        assertThat(taxonomy.typeCodesFor("DINING")).contains("050100", "050200", "050300");
        assertThat(taxonomy.typeCodesFor("DRINKS")).contains("050400", "050500", "050600");
        assertThat(taxonomy.typeCodesFor("ACTIVITY")).contains("050000", "060000", "110000", "141200");
        assertThat(taxonomy.typeCodesFor("CINEMA")).contains("080601");
        assertThat(taxonomy.typeCodesFor("HOTEL")).contains("100000");
        assertThat(taxonomy.typeCodesFor("SHOPPING")).contains("060000");
        assertThat(taxonomy.keywordsFor("DRINKS", List.of("quiet"))).contains("quiet", "bar", "coffee");
    }
}
