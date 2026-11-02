package de.christinecoenen.code.zapp.models.channels.custom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File

/**
 * Zapp TV: channel logos for the custom channels (the playlist's tvg-logo). Zapp's own channels use bundled
 * drawables; these are downloaded with the playlist into app storage - so they also show offline or while
 * the server is off - and refreshed weekly. Channels without a logo keep the generic icon.
 */
internal object CustomLogos {

	private const val DIR = "custom_logos"
	private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L

	private val bitmaps = LruCache<String, Bitmap>(64)

	private fun file(context: Context, channelId: String) = File(File(context.filesDir, DIR), "$channelId.img")

	/** Downloads missing or week-old logos. [logos] = channel id -> logo URL (may carry an access code). */
	fun refresh(context: Context, client: OkHttpClient, logos: Map<String, String>) {
		val dir = File(context.filesDir, DIR).apply { mkdirs() }
		val now = System.currentTimeMillis()
		for ((channelId, url) in logos) {
			val target = file(context, channelId)
			if (target.exists() && now - target.lastModified() < MAX_AGE_MS) continue
			try {
				client.newCall(Request.Builder().url(url).build()).execute().use { response ->
					val bytes = response.body?.bytes()
					// only keep what really is an image
					if (response.isSuccessful && bytes != null &&
						BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null
					) {
						val tmp = File(dir, "$channelId.tmp")
						tmp.writeBytes(bytes)
						tmp.renameTo(target)
						bitmaps.remove(channelId)
					}
				}
			} catch (e: Exception) {
				Timber.w("custom logo not loaded (%s)", e.javaClass.simpleName)  // never log the URL
			}
		}
	}

	/** Shows the downloaded logo of a custom channel in [view]; leaves the view alone otherwise. */
	fun apply(view: ImageView, channelId: String) {
		val bitmap = bitmaps.get(channelId) ?: file(view.context, channelId).takeIf { it.exists() }
			?.let { BitmapFactory.decodeFile(it.path) }
			?.also { bitmaps.put(channelId, it) }
		bitmap?.let { view.setImageBitmap(it) }
	}
}
