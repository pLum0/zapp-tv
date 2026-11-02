package de.christinecoenen.code.zapp.app.livestream.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import de.christinecoenen.code.zapp.models.channels.ChannelModel
import de.christinecoenen.code.zapp.repositories.ChannelRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map


class ChannelPlayerActivityViewModel(channelRepository: ChannelRepository) : ViewModel() {

	private val channelList = channelRepository.getChannelList()
	private var channelId = MutableSharedFlow<String>(replay = 1)

	private val channelFlow = channelId
		.map { channelId -> channelList[channelId]!! }

	// Zapp TV: switch only within the channel's group (custom playlist channels vs. Zapp's own)
	val previousChannelLiveData = channelFlow
		.map { channel ->
			val group = de.christinecoenen.code.zapp.models.channels.custom.CustomChannels.sameGroup(channelList, channel)
			val index = group.indexOfFirst { it.id == channel.id }
			if (index <= 0) null else group[index - 1]
		}
		.asLiveData(viewModelScope.coroutineContext)

	val nextChannelLiveData = channelFlow
		.map { channel ->
			val group = de.christinecoenen.code.zapp.models.channels.custom.CustomChannels.sameGroup(channelList, channel)
			val index = group.indexOfFirst { it.id == channel.id }
			if (index >= group.size - 1) null else group[index + 1]
		}
		.asLiveData(viewModelScope.coroutineContext)

	val channel = channelFlow.asLiveData(viewModelScope.coroutineContext)

	suspend fun setChannelId(channelId: String): ChannelModel {
		this.channelId.emit(channelId)
		return channelFlow.first()
	}

}
