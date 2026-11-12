package app.expirymate.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Looks up a product name from its barcode using the free Open Food Facts database. */
object ProductLookup {

    /**
     * Returns null when the product is unknown or the network is unavailable;
     * the user then types the name themselves.
     */
    suspend fun name(barcode: String): String? = withContext(Dispatchers.IO) {
        if (barcode.isEmpty() || !barcode.all { it.isDigit() }) return@withContext null
        runCatching {
            val url = URL("https://world.openfoodfacts.org/api/v2/product/$barcode.json?fields=product_name,brands")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("User-Agent", "ExpiryMate/1.0 (Android)")
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val product = JSONObject(body).optJSONObject("product") ?: return@runCatching null
                combine(text(product, "product_name"), text(product, "brands").substringBefore(',').trim())
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private fun text(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key).trim()

    private fun combine(name: String, brand: String): String? = when {
        name.isEmpty() -> brand.ifEmpty { null }
        brand.isEmpty() || name.contains(brand, ignoreCase = true) -> name
        else -> "$brand $name"
    }
}
