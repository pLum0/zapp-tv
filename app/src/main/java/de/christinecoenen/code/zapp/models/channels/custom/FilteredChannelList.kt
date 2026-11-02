package de.christinecoenen.code.zapp.models.channels.custom

import de.christinecoenen.code.zapp.models.channels.ChannelModel
import de.christinecoenen.code.zapp.models.channels.ISortableChannelList

/**
 * Zapp TV: a view on a sortable channel list that only shows some of its channels - the Live tab shows
 * Zapp's own channels, the "Eigene" tab the ones from the user's playlists. Sorting and reloading are
 * delegated, so channel order and visibility (settings > channel selection) keep working.
 *
 * Always filters the base list's current content (its `list` property is fixed at construction time,
 * iterating it is not).
 */
class FilteredChannelList(
	private val base: ISortableChannelList,
	private val keep: (ChannelModel) -> Boolean,
) : ISortableChannelList {

	private val current: List<ChannelModel>
		get() = base.iterator().asSequence().filter(keep).toList()

	override val list: List<ChannelModel>
		get() = current

	override fun get(index: Int) = current[index]

	override fun get(id: String) = current.firstOrNull { it.id == id }

	override fun size() = current.size

	override fun iterator() = current.iterator()

	override fun indexOf(channelId: String) = current.indexOfFirst { it.id == channelId }

	override fun reload() = base.reload()

	override fun reloadChannelOrder() = base.reloadChannelOrder()

	override fun persistChannelOrder() = base.persistChannelOrder()
}
