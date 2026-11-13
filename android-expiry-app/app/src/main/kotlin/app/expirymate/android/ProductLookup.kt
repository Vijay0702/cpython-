package app.expirymate.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks up a product name from its barcode in the free, volunteer-run
 * Open Food Facts family of databases.
 */
object ProductLookup {
    // In order of preference when a barcode is listed in more than one.
    private val hosts = listOf(
        "world.openfoodfacts.org",      // food and drink
        "world.openbeautyfacts.org",    // cosmetics and toiletries
        "world.openproductsfacts.org",  // everything else
    )

    /**
     * Returns null when the product is unknown or the network is unavailable;
     * the user then types the name themselves.
     */
    suspend fun name(barcode: String): String? = coroutineScope {
        if (barcode.isEmpty() || !barcode.all { it in '0'..'9' }) return@coroutineScope null
        hosts
            .map { host -> async(Dispatchers.IO) { fetch(host, barcode) } }
            .awaitAll()
            .firstOrNull { it != null }
    }

    private fun fetch(host: String, barcode: String): String? = runCatching {
        val url = URL("https://$host/api/v2/product/$barcode.json?fields=product_name,brands")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.setRequestProperty("User-Agent", "ExpiryMate/1.0 (Android)")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optJSONObject("product")?.let { product ->
                combine(text(product, "product_name"), text(product, "brands").substringBefore(',').trim())
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun text(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key).trim()

    private fun combine(name: String, brand: String): String? = when {
        name.isEmpty() -> brand.ifEmpty { null }
        brand.isEmpty() || name.contains(brand, ignoreCase = true) -> name
        else -> "$brand $name"
    }
}
