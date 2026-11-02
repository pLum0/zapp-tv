package de.christinecoenen.code.zapp.models.channels.custom

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import de.christinecoenen.code.zapp.R
import de.christinecoenen.code.zapp.app.livestream.model.LiveShow
import org.joda.time.DateTime
import de.christinecoenen.code.zapp.models.channels.ChannelModel
import de.christinecoenen.code.zapp.models.channels.IChannelList
import de.christinecoenen.code.zapp.models.channels.ISortableChannelList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Zapp TV: additional live channels from user supplied M3U playlists (e.g. a TVheadend server or event
 * streams), shown after the bundled channels.
 *
 * The playlist URLs are a setting (any whitespace separates several). They are downloaded in the
 * background - at app start and whenever the setting changes - into one cached M3U file, so the blocking
 * channel list ([de.christinecoenen.code.zapp.models.channels.json.JsonChannelList]) only reads a small
 * local file. A failed download keeps the last good cache.
 *
 * Kept in its own package with as few hooks into Zapp's code as possible, so the fork rebases cleanly.
 */
object CustomChannels {

	const val ID_PREFIX = "custom_"
	private const val CACHE_FILE = "custom_channels.m3u"
	private const val COLOR = 0xFF455A64.toInt()

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val client by lazy {
		OkHttpClient.Builder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(30, TimeUnit.SECONDS)
			.build()
	}

	// kept as a field: SharedPreferences only holds listeners weakly
	private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

	fun isCustom(channelId: String) = channelId.startsWith(ID_PREFIX)

	/** Channel list for a tab: only the custom channels ("Eigene" tab) or only Zapp's own (Live tab). */
	fun forTab(list: ISortableChannelList, customOnly: Boolean): ISortableChannelList =
		FilteredChannelList(list) { isCustom(it.id) == customOnly }

	/** The channels the player may switch to from [channel]: the same group (custom or Zapp's own). */
	fun sameGroup(list: IChannelList, channel: ChannelModel): List<ChannelModel> =
		list.iterator().asSequence().filter { isCustom(it.id) == isCustom(channel.id) }.toList()

	/**
	 * Call once from Application.onCreate: refreshes now and after every change of the setting.
	 */
	fun init(context: Context) {
		val app = context.applicationContext
		appContext = app
		val key = app.getString(R.string.pref_key_custom_channel_urls)
		listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
			if (changed == key) refreshInBackground(app)
		}
		PreferenceManager.getDefaultSharedPreferences(app).registerOnSharedPreferenceChangeListener(listener)
		refreshInBackground(app)
	}

	fun refreshInBackground(context: Context) {
		scope.launch { refresh(context) }
	}

	/**
	 * Channels from the cached playlist; empty if nothing is configured or nothing was loaded yet.
	 */
	fun cached(context: Context): List<ChannelModel> {
		val file = cacheFile(context)
		if (!file.exists()) return emptyList()
		return try {
			parse(file.readText())
		} catch (e: IOException) {
			Timber.w(e, "custom channels: cache not readable")
			emptyList()
		}
	}

	/**
	 * Programme line for a custom channel (there is no programme guide for them yet): its playlist group.
	 */
	fun describe(channelId: String): String {
		val context = appContext ?: return ""
		// the same text Zapp shows for its own channels without programme data
		return context.getString(R.string.activity_channel_detail_info_error)
	}

	private var appContext: Context? = null

	/**
	 * Downloads all configured playlists into the cache. Returns the number of channels, or null if
	 * nothing could be loaded (the old cache then stays).
	 */
	suspend fun refresh(context: Context): Int? = withContext(Dispatchers.IO) {
		val urls = urls(context)
		val file = cacheFile(context)
		if (urls.isEmpty()) {
			file.delete()
			return@withContext 0
		}
		val loaded = urls.mapNotNull { url ->
			try {
				client.newCall(Request.Builder().url(url).build()).execute().use { response ->
					if (response.isSuccessful) response.body?.string()?.let { url to it } else {
						Timber.w("custom channels: HTTP %d for a playlist", response.code)
						null
					}
				}
			} catch (e: Exception) {
				// never log the URL: it may carry an access code
				Timber.w("custom channels: playlist not loaded (%s)", e.javaClass.simpleName)
				null
			}
		}
		if (loaded.isEmpty()) return@withContext null
		val combined = "#EXTM3U\n" + loaded.joinToString("\n") { (_, text) ->
			text.lineSequence().filterNot { it.trim().startsWith("#EXTM3U") }.joinToString("\n").trim()
		} + "\n"
		val tmp = File(file.parentFile, "$CACHE_FILE.tmp")
		tmp.writeText(combined)
		tmp.renameTo(file)
		// programme guide: one XMLTV source per playlist, if it has one
		CustomEpg.rememberSources(context, loaded.mapNotNull { (url, text) -> CustomEpg.sourceFor(url, text) })
		CustomEpg.refresh(context, client)
		val channels = parse(combined)
		CustomLogos.refresh(context, client, logoOf.filterKeys { id -> channels.any { it.id == id } })
		channels.size
	}

	/** Zapp TV: puts the downloaded logo of a custom channel into a logo view (no-op for Zapp's channels). */
	fun applyLogo(view: android.widget.ImageView, channel: ChannelModel) {
		if (isCustom(channel.id)) CustomLogos.apply(view, channel.id)
	}

	/**
	 * What the programme line shows for a custom channel: the current programme from the guide, else the
	 * playlist group. A guide older than 6 hours is refreshed in the background.
	 */
	fun liveShow(channelId: String): LiveShow {
		val context = appContext ?: return LiveShow("")
		if (CustomEpg.isStale(context)) {
			scope.launch { CustomEpg.refresh(context, client) }
		}
		cached(context)  // fills tvgOf
		val programme = tvgOf[channelId]?.let { CustomEpg.current(context, it) }
			?: return LiveShow(describe(channelId))
		return LiveShow(
			title = programme.title,
			subtitle = programme.subtitle,
			description = programme.description,
			startTime = DateTime(programme.start),
			endTime = DateTime(programme.stop),
		)
	}

	private fun urls(context: Context): List<String> {
		val key = context.getString(R.string.pref_key_custom_channel_urls)
		val value = PreferenceManager.getDefaultSharedPreferences(context).getString(key, "") ?: ""
		return value.split(Regex("\\s+")).filter { it.startsWith("http://") || it.startsWith("https://") }
	}

	private fun cacheFile(context: Context) = File(context.filesDir, CACHE_FILE)

	// tvg-id (= XMLTV channel id) and logo URL per channel id, filled by parse()
	// (filled on IO threads, read by the UI's program info flow)
	private val tvgOf = java.util.concurrent.ConcurrentHashMap<String, String>()
	private val logoOf = java.util.concurrent.ConcurrentHashMap<String, String>()

	private val attribute = Regex("""([\w-]+)="([^"]*)"""")

	/**
	 * Minimal M3U/M3U8 playlist parser: "#EXTINF:<duration> key="value" ...,<name>" followed by the URL.
	 */
	internal fun parse(text: String): List<ChannelModel> {
		val channels = mutableListOf<ChannelModel>()
		val seen = mutableSetOf<String>()
		var info: String? = null
		for (raw in text.lineSequence()) {
			val line = raw.trim()
			when {
				line.isEmpty() -> continue
				line.startsWith("#EXTINF", ignoreCase = true) -> info = line
				line.startsWith("#") -> continue
				else -> {
					val extinf = info ?: ""
					info = null
					// the name follows the first comma that is outside of a quoted attribute value
					val attrs = attribute.findAll(extinf).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
					val name = nameOf(extinf).ifBlank { line.substringAfterLast('/').substringBefore('?') }
					val key = attrs["tvg-id"]?.takeIf { it.isNotBlank() } ?: (name + "|" + line.substringBefore('?'))
					var id = ID_PREFIX + sha1(key).take(16)
					while (!seen.add(id)) id += "_"
					attrs["tvg-id"]?.takeIf { it.isNotBlank() }?.let { tvgOf[id] = it }
					attrs["tvg-logo"]?.takeIf { it.startsWith("http") }?.let { logoOf[id] = it }
					channels += ChannelModel(
						id = id,
						name = name,
						subtitle = name,
						streamUrl = line,
						drawableId = R.drawable.ic_custom_channel,
						color = COLOR,
					)
				}
			}
		}
		return channels
	}

	private fun nameOf(extinf: String): String {
		var quoted = false
		extinf.forEachIndexed { i, c ->
			if (c == '"') quoted = !quoted
			if (c == ',' && !quoted) return extinf.substring(i + 1).trim()
		}
		return ""
	}

	private fun sha1(text: String): String =
		MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
