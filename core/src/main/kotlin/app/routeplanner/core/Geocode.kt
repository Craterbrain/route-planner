package app.routeplanner.core

/** Resolves a street address to a rooftop/door position, or null if unknown. */
fun interface Geocoder {
    fun geocode(address: String): LatLng?
}

/**
 * Remembers where drivers actually drop off, per normalized address. Checked
 * before geocoding so corrections survive across routes.
 */
interface AddressMemory {
    fun access(address: String): LatLng?
    fun remember(address: String, access: LatLng)
}

class InMemoryAddressMemory : AddressMemory {
    private val m = HashMap<String, LatLng>()
    override fun access(address: String) = m[normalizeAddress(address)]
    override fun remember(address: String, access: LatLng) { m[normalizeAddress(address)] = access }
}

fun normalizeAddress(a: String) = a.lowercase().replace(Regex("\\s+"), " ").trim()
