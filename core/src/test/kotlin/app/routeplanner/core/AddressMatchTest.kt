package app.routeplanner.core

import org.junit.Assert.*
import org.junit.Test

class AddressMatchTest {
    private fun s(title: String, subtitle: String = "", p: LatLng? = LatLng(35.9, -84.9)) = Suggestion(title, subtitle, p, SuggestSource.GOOGLE)

    @Test fun sameRoadDifferentSpelling() {
        assertTrue(AddressMatch.matches("8681 US-70, Crossville TN 38555", s("8681 Highway 70 East", "Crab Orchard, TN 38555")))
        assertTrue(AddressMatch.matches("8681 Hwy 70 E Apt 4, Crossville TN 38555", s("8681 US-70", "Crossville, TN 38555")))
        assertTrue(AddressMatch.matches("100 Industrial Way, Nashville TN 37201", s("100 Industrial Way", "Nashville, TN 37201")))
    }

    @Test fun differentHouseNumberOrStreetIsRejected() {
        assertFalse(AddressMatch.matches("8681 Hwy 70 E, Crossville TN 38555", s("8618 Highway 70 East", "Crossville, TN 38555")))
        assertFalse(AddressMatch.matches("100 Main St, Crossville TN 38555", s("100 Oak Street", "Crossville, TN 38555")))
    }

    @Test fun zipMustAgreeWhenBothHaveOne() {
        assertFalse(AddressMatch.matches("22 Oak Ave, Town TN 38555", s("22 Oak Avenue", "Town, TN 37201")))
        assertTrue(AddressMatch.matches("22 Oak Ave, Town TN 38555", s("22 Oak Avenue", "Town, TN")))
    }

    @Test fun noPointOrNoHouseNumberIsRejected() {
        assertFalse(AddressMatch.matches("22 Oak Ave", s("22 Oak Avenue", p = null)))
        assertFalse(AddressMatch.matches("Oak Ave", s("22 Oak Avenue")))
        assertFalse(AddressMatch.matches("22 Oak Ave", s("Oak Avenue Park")))
    }
}
