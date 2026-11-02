package de.christinecoenen.code.zapp.models.channels.custom

import android.content.Context
import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Zapp TV: programme guide for the custom channels, from XMLTV.
 *
 * Source per playlist: an `url-tvg` / `x-tvg-url` attribute in its #EXTM3U line, otherwise - for a
 * TVheadend playlist - the matching XMLTV export (".../playlist/auth/tagname/X?...&auth=C" ->
 * ".../xmltv/tagname/X?auth=C"). Only the next [KEEP_HOURS] hours are kept, as small JSON, keyed by the
 * XMLTV channel id (= the playlist's tvg-id). Refreshed with the playlists and when older than [MAX_AGE_MS].
 */
internal object CustomEpg {

	class Programme(val start: Long, val stop: Long, val title: String, val subtitle: String?, val description: String?)

	private const val CACHE_FILE = "custom_epg.json"
	private const val SOURCES_FILE = "custom_epg_sources.txt"
	private const val KEEP_HOURS = 36
	private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

	@Volatile
	private var programmes: Map<String, List<Programme>>? = null

	/** The XMLTV address for a playlist, or null if none is known. */
	fun sourceFor(playlistUrl: String, playlistText: String): String? {
		val header = playlistText.lineSequence().firstOrNull { it.trim().startsWith("#EXTM3U") } ?: ""
		Regex("""(?:url-tvg|x-tvg-url)="([^"]+)"""").find(header)?.let { return it.groupValues[1].split(',')[0] }
		val uri = try { URI(playlistUrl) } catch (e: Exception) { return null }
		val path = uri.path ?: return null
		if (!path.contains("/playlist/")) return null
		val xmltvPath = path.replace(Regex("/playlist/(auth/)?"), "/xmltv/")
		val auth = (uri.rawQuery ?: "").split('&').firstOrNull { it.startsWith("auth=") }
		return URI(uri.scheme, null, uri.host, uri.port, null, null, null).toString() +
			xmltvPath + (auth?.let { "?$it" } ?: "")
	}

	fun rememberSources(context: Context, sources: List<String>) {
		File(context.filesDir, SOURCES_FILE).writeText(sources.joinToString("\n"))
	}

	fun isStale(context: Context): Boolean {
		val file = File(context.filesDir, CACHE_FILE)
		return !file.exists() || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS
	}

	/** Downloads all remembered XMLTV sources into the cache. Keeps the old cache if nothing loads. */
	fun refresh(context: Context, client: OkHttpClient) {
		val sources = File(context.filesDir, SOURCES_FILE).takeIf { it.exists() }?.readLines()
			?.filter { it.isNotBlank() } ?: emptyList()
		if (sources.isEmpty()) return
		val now = System.currentTimeMillis()
		val until = now + KEEP_HOURS * 60 * 60 * 1000L
		val all = mutableMapOf<String, MutableList<Programme>>()
		var loaded = false
		for (url in sources) {
			try {
				client.newCall(Request.Builder().url(url).build()).execute().use { response ->
					val body = response.body
					if (!response.isSuccessful || body == null) {
						Timber.w("custom epg: HTTP %d", response.code)
						return@use
					}
					parse(body.byteStream(), now, until, all)
					loaded = true
				}
			} catch (e: Exception) {
				Timber.w("custom epg: not loaded (%s)", e.javaClass.simpleName)  // never log the URL (auth code)
			}
		}
		if (!loaded) return
		val json = JSONObject()
		for ((channel, list) in all) {
			json.put(channel, JSONArray(list.sortedBy { it.start }.map {
				JSONObject().put("a", it.start).put("b", it.stop).put("t", it.title)
					.put("s", it.subtitle ?: "").put("d", it.description ?: "")
			}))
		}
		val file = File(context.filesDir, CACHE_FILE)
		val tmp = File(context.filesDir, "$CACHE_FILE.tmp")
		tmp.writeText(json.toString())
		tmp.renameTo(file)
		programmes = null  // reload on next access
	}

	/** The programme running now on the XMLTV channel [xmltvId], if known. */
	fun current(context: Context, xmltvId: String): Programme? {
		val now = System.currentTimeMillis()
		return load(context)[xmltvId]?.firstOrNull { it.start <= now && now < it.stop }
	}

	private fun load(context: Context): Map<String, List<Programme>> {
		programmes?.let { return it }
		val file = File(context.filesDir, CACHE_FILE)
		val result = mutableMapOf<String, List<Programme>>()
		if (file.exists()) {
			try {
				val json = JSONObject(file.readText())
				for (channel in json.keys()) {
					val arr = json.getJSONArray(channel)
					result[channel] = (0 until arr.length()).map { i ->
						val o = arr.getJSONObject(i)
						Programme(o.getLong("a"), o.getLong("b"), o.getString("t"),
							o.optString("s").ifBlank { null }, o.optString("d").ifBlank { null })
					}
				}
			} catch (e: Exception) {
				Timber.w(e, "custom epg: cache not readable")
			}
		}
		programmes = result
		return result
	}

	private fun parse(input: InputStream, now: Long, until: Long, into: MutableMap<String, MutableList<Programme>>) {
		val format = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ROOT)
		val parser = Xml.newPullParser()
		parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
		parser.setInput(input, null)
		var channel: String? = null
		var start = 0L
		var stop = 0L
		var title: String? = null
		var subtitle: String? = null
		var description: String? = null
		var field: String? = null
		while (parser.next() != XmlPullParser.END_DOCUMENT) {
			when (parser.eventType) {
				XmlPullParser.START_TAG -> when (parser.name) {
					"programme" -> {
						channel = parser.getAttributeValue(null, "channel")
						start = parseTime(format, parser.getAttributeValue(null, "start"))
						stop = parseTime(format, parser.getAttributeValue(null, "stop"))
						title = null; subtitle = null; description = null
					}
					"title", "sub-title", "desc" -> field = parser.name
				}
				XmlPullParser.TEXT -> when (field) {
					"title" -> title = (title ?: "") + parser.text
					"sub-title" -> subtitle = (subtitle ?: "") + parser.text
					"desc" -> description = (description ?: "") + parser.text
				}
				XmlPullParser.END_TAG -> when (parser.name) {
					"title", "sub-title", "desc" -> field = null
					"programme" -> {
						val ch = channel
						if (ch != null && title != null && stop > now && start < until) {
							into.getOrPut(ch) { mutableListOf() }
								.add(Programme(start, stop, title!!.trim(), subtitle?.trim(), description?.trim()))
						}
					}
				}
			}
		}
	}

	private fun parseTime(format: SimpleDateFormat, value: String?): Long =
		try { format.parse(value ?: "")?.time ?: 0L } catch (e: Exception) { 0L }
}
