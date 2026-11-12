package app.expirymate.android

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * All items, kept in memory and mirrored to a small JSON file.
 * The screens and the notification receivers share this one instance.
 */
object ItemStore {
    private val state = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = state
    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        val file = file(context)
        if (file.exists()) {
            state.value = runCatching { parse(file.readText()) }.getOrDefault(emptyList())
        }
        loaded = true
    }

    @Synchronized
    fun update(context: Context, transform: (List<Item>) -> List<Item>) {
        load(context)
        state.value = transform(state.value)
        file(context).writeText(serialize(state.value))
    }

    private fun file(context: Context) = File(context.filesDir, "items.json")

    private fun serialize(items: List<Item>): String {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("barcode", item.barcode ?: JSONObject.NULL)
                    .put("expiry", item.expiry.toString())
                    .put("status", item.status)
                    .put("snoozedUntil", item.snoozedUntil ?: JSONObject.NULL)
            )
        }
        return array.toString()
    }

    private fun parse(text: String): List<Item> {
        val array = JSONArray(text)
        return (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            Item(
                id = o.getString("id"),
                name = o.getString("name"),
                barcode = if (o.isNull("barcode")) null else o.getString("barcode"),
                expiry = LocalDate.parse(o.getString("expiry")),
                status = o.optString("status", Item.ACTIVE),
                snoozedUntil = if (o.isNull("snoozedUntil")) null else o.getLong("snoozedUntil"),
            )
        }
    }
}
