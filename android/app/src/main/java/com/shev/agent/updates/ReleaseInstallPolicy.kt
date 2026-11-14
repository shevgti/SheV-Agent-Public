package com.shev.agent.updates

internal data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val signerSha256: Set<String>
)

/** Fail-closed validation performed before handing an APK to Android's package installer. */
internal object ReleaseInstallPolicy {
    fun validate(
        expectedPackageName: String,
        expectedVersionCode: Long,
        installedSignerSha256: Set<String>,
        candidate: ApkIdentity
    ) {
        check(candidate.packageName == expectedPackageName) {
            "APK предназначен для другого приложения: ${candidate.packageName}."
        }
        check(candidate.versionCode == expectedVersionCode) {
            "Версия APK не совпадает с метаданными релиза."
        }
        check(installedSignerSha256.isNotEmpty() && candidate.signerSha256.isNotEmpty()) {
            "Не удалось проверить подпись установленного приложения или APK."
        }
        check(candidate.signerSha256 == installedSignerSha256) {
            "Подпись APK отличается от подписи установленного приложения. Установка отменена."
        }
    }
}
