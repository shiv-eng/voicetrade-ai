package com.quietstack.voicetrade.data.repository

import com.quietstack.voicetrade.data.remote.api.VoiceTradeApi
import com.quietstack.voicetrade.data.remote.ws.OkHttpSessionEventsSocket
import com.quietstack.voicetrade.data.remote.ws.SessionEventsSocket
import com.quietstack.voicetrade.data.rtc.AgoraRtcDataSource
import com.quietstack.voicetrade.data.rtc.RtcDataSource
import javax.inject.Inject
import javax.inject.Singleton

/** The live backend, the Agora engine and the events WebSocket, behind one injectable seam (handy for tests). */
@Singleton
class BackendGateway @Inject constructor(
    private val api: VoiceTradeApi,
    private val agora: AgoraRtcDataSource,
    private val socket: OkHttpSessionEventsSocket,
) {
    fun api(): VoiceTradeApi = api
    fun rtc(): RtcDataSource = agora
    fun socket(): SessionEventsSocket = socket
}
