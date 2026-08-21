package com.adbcontrol.controlled.executor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.adbcontrol.shared.model.Command
import com.adbcontrol.shared.model.CommandCategory
import com.adbcontrol.shared.model.ExecutionResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 普通 Context API 执行器。README 3.3 L5 兜底。
 *
 * 仅用普通应用权限可做的事:
 * - 音量调节(AudioManager)
 * - 本地通知(NotificationManager)
 * - Toast
 */
@Singleton
class NormalExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
) : CommandExecutor {

    override val name: String = "Normal"

    private val audio: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun isAvailable(): Boolean = true

    override fun supports(command: Command): Boolean = when (command.category) {
        CommandCategory.SYSTEM -> command.action == "setVolume" || command.action == "setRingerMode"
        else -> false
    }

    override suspend fun execute(command: Command, commandId: String): ExecutionResult {
        val started = System.currentTimeMillis()
        return runCatching {
            when {
                command.category == CommandCategory.SYSTEM && command.action == "setVolume" -> {
                    val v = command.params["value"]?.toIntOrNull()
                        ?: return fail(commandId, "missing value")
                    val stream = streamType(command.params["stream"] ?: "media")
                    audio.setStreamVolume(stream, v, 0)
                    ok(commandId, "vol=$v", System.currentTimeMillis() - started)
                }
                command.category == CommandCategory.SYSTEM && command.action == "setRingerMode" -> {
                    val mode = command.params["mode"]?.toIntOrNull()
                        ?: return fail(commandId, "missing mode")
                    audio.ringerMode = mode
                    ok(commandId, "ringer=$mode", System.currentTimeMillis() - started)
                }
                else -> noPath(commandId)
            }
        }.getOrElse {
            fail(commandId, "exception=${it.message}", System.currentTimeMillis() - started)
        }
    }

    private fun streamType(name: String): Int = when (name) {
        "ring" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "system" -> AudioManager.STREAM_SYSTEM
        else -> AudioManager.STREAM_MUSIC
    }
}
