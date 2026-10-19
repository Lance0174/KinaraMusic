/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 在线搜索状态机（纯逻辑，JVM 可测）：
 *  - 状态：Idle/Loading/Error/Empty/Data；
 *  - 失败优先于空结果（全部源挂掉时显示错误而非"没搜到"）；
 *  - 代次号丢弃晚到结果；同词补全不清空旧结果（避免闪烁）。
 */
package com.kinara.music.online

data class OnlineSearchState(
    val phase: Phase = Phase.IDLE,
    val results: List<OnlineResult> = emptyList(),
    val keyword: String = "",
    val searching: Boolean = phase == Phase.LOADING,
) {
    enum class Phase { IDLE, LOADING, ERROR, EMPTY, DATA }

    val displayTracks: List<OnlineTrack> = mergeSearchTracks(results)

    fun tracksFrom(enabled: List<String>): List<OnlineTrack> = mergeSearchTracks(results.filter { it.platform in enabled })

    val hasFailure: Boolean get() = results.any { it.errorMessage != null }

    companion object {
        /**
         * 归约：每收到一路结果调用一次。
         * @param generation 本次请求代次号（结果携带）
         * @param current 请求发起时的代次号（闭包捕获）
         */
        fun reduce(
            state: OnlineSearchState,
            result: OnlineResult,
            generation: Long,
            currentGeneration: Long,
        ): OnlineSearchState {
            if (generation != currentGeneration) return state // 晚到丢弃
            val results = (state.results.filterNot { it.platform == result.platform } + result).sortedBy { sourceRank(it.platform) }
            val merged = OnlineSearchState(Phase.LOADING, results, state.keyword)
            return when {
                merged.displayTracks.isNotEmpty() -> merged.copy(phase = Phase.DATA)
                else -> merged // 保持 LOADING 等其余源
            }
        }

        fun finalize(state: OnlineSearchState): OnlineSearchState = when {
            state.displayTracks.isNotEmpty() -> state.copy(phase = Phase.DATA, searching = false)
            state.hasFailure -> state.copy(phase = Phase.ERROR, searching = false)
            else -> state.copy(phase = if (state.keyword.isBlank()) Phase.IDLE else Phase.EMPTY, searching = false)
        }
    }
}

/** Merge only the same credited recording; covers/live versions and ambiguous artists stay separate. */
internal fun mergeSearchTracks(results: List<OnlineResult>): List<OnlineTrack> {
    val songs = mutableListOf<OnlineTrack>()
    results.sortedBy { sourceRank(it.platform) }.flatMap { it.tracks }
        .distinctBy { it.platform to it.id }.forEach { candidate ->
            val index = songs.indexOfFirst { it.platform != candidate.platform && sameRecording(it.toTrack("320"), candidate) }
            if (index < 0) songs += candidate
            else if (songs[index].picUrl.isNullOrBlank() && !candidate.picUrl.isNullOrBlank())
                songs[index] = songs[index].copy(picUrl = candidate.picUrl)
        }
    return songs
}
