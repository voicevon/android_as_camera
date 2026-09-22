package com.von.cameraapp.ui

import com.von.cameraapp.service.CameraStreamService

/**
 * UI 状态桥：Service 的 UiListener 回调在此汇聚，Activity 注册观察。
 */
class CameraViewModel {

    private val listeners = mutableListOf<(CameraStreamService.UiState) -> Unit>()

    fun observe(listener: (CameraStreamService.UiState) -> Unit) {
        listeners.add(listener)
    }

    fun onState(state: CameraStreamService.UiState) {
        listeners.forEach { it(state) }
    }
}
