package org.techwithkaushik.formsnap.feature.capture

expect class CacheJanitor {
    suspend fun clearTemporaryAssets()
}
