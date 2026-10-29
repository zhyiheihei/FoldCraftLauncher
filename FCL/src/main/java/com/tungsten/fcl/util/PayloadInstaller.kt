package com.tungsten.fcl.util

import android.content.Context
import android.util.Log
import com.tungsten.fcl.setting.Accounts
import com.tungsten.fcl.setting.Profiles
import com.tungsten.fclauncher.utils.FCLPath
import com.tungsten.fclcore.auth.offline.OfflineAccountFactory
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.File
import java.util.Properties

/**
 * 随包分发的游戏文件装配器。
 *
 * APK 的 assets 里可以带 [PAYLOAD_ASSET]（zip）与 [CONFIG_ASSET]（属性），
 * 首次启动时一次性释放到共享游戏目录，之后启动不再需要联网下载素材与运行库。
 * 没有这两个 asset 的构建（上游原样构建）会整体跳过，行为与上游一致。
 */
object PayloadInstaller {
    private const val TAG = "PayloadInstaller"
    private const val PAYLOAD_ASSET = "payload.zip"
    private const val CONFIG_ASSET = "payload.properties"
    private const val MARKER = ".payload-version"

    private fun readConfig(context: Context): Properties? {
        val properties = Properties()
        return runCatching {
            context.assets.open(CONFIG_ASSET).use { properties.load(it) }
            properties
        }.getOrNull()
    }

    fun hasPayload(context: Context): Boolean =
        runCatching { context.assets.open(PAYLOAD_ASSET).close() }.isSuccess

    private fun markerFile(): File = File(FCLPath.SHARED_COMMON_DIR, MARKER)

    /** 需要释放的条件：APK 带载荷，且已安装的载荷版本号与 APK 内声明的不一致。 */
    fun needsInstall(context: Context): Boolean {
        if (!hasPayload(context)) return false
        val wanted = readConfig(context)?.getProperty("payloadVersion") ?: return false
        val installed = markerFile().takeIf { it.isFile }?.readText()?.trim()
        return installed != wanted
    }

    /**
     * 解压载荷到共享游戏目录。调用方必须在 IO 线程执行。
     * @param onProgress 已写入字节数、当前条目名、总字节数（总字节数未知时为 -1）
     */
    fun install(context: Context, onProgress: (Long, String, Long) -> Unit) {
        val config = readConfig(context) ?: throw IllegalStateException("missing $CONFIG_ASSET")
        val target = File(FCLPath.SHARED_COMMON_DIR)
        if (!target.isDirectory && !target.mkdirs()) {
            throw IllegalStateException("cannot create ${target.absolutePath}")
        }
        val total = runCatching {
            context.assets.openFd(PAYLOAD_ASSET).use { it.length }
        }.getOrDefault(-1L)

        var written = 0L
        var lastReport = 0L
        val buffer = ByteArray(1 shl 16)

        context.assets.open(PAYLOAD_ASSET).use { input ->
            ZipArchiveInputStream(input, "UTF-8").use { zip ->
                while (true) {
                    val entry = zip.nextZipEntry ?: break
                    val name = entry.name
                    if (name.startsWith("__MACOSX") || name == "/") continue
                    val out = File(target, name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                        continue
                    }
                    if (!out.canonicalPath.startsWith(target.canonicalPath)) {
                        Log.w(TAG, "skip unsafe entry: $name")
                        continue
                    }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { os ->
                        while (true) {
                            val read = zip.read(buffer)
                            if (read <= 0) break
                            os.write(buffer, 0, read)
                            written += read
                            if (written - lastReport >= (8L shl 20)) {
                                lastReport = written
                                onProgress(written, name, total)
                            }
                        }
                    }
                    out.setLastModified(entry.time.takeIf { it > 0 } ?: System.currentTimeMillis())
                }
            }
        }

        markerFile().writeText(config.getProperty("payloadVersion"))
        onProgress(written, "", total)
    }

    /**
     * 首次进入启动器后补上默认账号与选中版本，让玩家打开即能直接点启动。
     * 已有账号/已选版本时不覆盖玩家自己的选择。
     */
    fun applyDefaults(context: Context) {
        if (!hasPayload(context)) return
        val config = readConfig(context) ?: return
        val versionId = config.getProperty("versionId")?.takeIf { it.isNotBlank() } ?: return
        val playerName = config.getProperty("offlineName")?.takeIf { it.isNotBlank() } ?: "Player"

        runCatching {
            if (Accounts.getAccounts().isEmpty()) {
                val account = Accounts.FACTORY_OFFLINE.create(
                    playerName,
                    OfflineAccountFactory.getUUIDFromUserName(playerName)
                )
                Accounts.addAccount(account)
                Accounts.setSelectedAccount(account)
            } else if (Accounts.getSelectedAccount() == null) {
                Accounts.setSelectedAccount(Accounts.getAccounts()[0])
            }
        }.onFailure { Log.w(TAG, "failed to prepare account", it) }

        runCatching {
            val profile = Profiles.getSelectedProfile()
            if (profile.selectedVersion == null) {
                profile.selectedVersion = versionId
            }
        }.onFailure { Log.w(TAG, "failed to select version", it) }
    }
}
