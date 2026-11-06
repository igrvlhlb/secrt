package io.github.igrvlhlb.secrt.backup.webdav

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun defaultHttpEngine(): HttpClientEngineFactory<*> = OkHttp
