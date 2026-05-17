package eu.darken.octi.desktop.protocol.octiserver

import eu.darken.octi.desktop.common.log.Logging
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.protocol.module.ModuleId
import eu.darken.octi.desktop.protocol.octiserver.dto.AccountStorageResponse
import eu.darken.octi.desktop.protocol.octiserver.dto.BlobListResponse
import eu.darken.octi.desktop.protocol.octiserver.dto.CreateSessionRequest
import eu.darken.octi.desktop.protocol.octiserver.dto.CreateSessionResponse
import eu.darken.octi.desktop.protocol.octiserver.dto.DevicesResponse
import eu.darken.octi.desktop.protocol.octiserver.dto.FinalizeSessionRequest
import eu.darken.octi.desktop.protocol.octiserver.dto.FinalizeSessionResponse
import eu.darken.octi.desktop.protocol.octiserver.dto.ModuleCommitRequest
import eu.darken.octi.desktop.protocol.octiserver.dto.RegisterResponse
import eu.darken.octi.desktop.protocol.octiserver.dto.ResetRequest
import eu.darken.octi.desktop.protocol.octiserver.dto.SessionStatus
import eu.darken.octi.desktop.protocol.octiserver.dto.ShareCodeResponse
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.DeviceId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging as KtorLogging
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Instant

/**
 * Ktor-based client for the OctiServer REST API. Wire-compatible drop-in replacement for the
 * Retrofit `OctiServerApi` interface used by app-main. Every endpoint method is intended to
 * produce a byte-identical request to what the Android Retrofit client sends — verified by
 * HTTP snapshot tests in the CI safety net phase.
 *
 * Auth: HTTP Basic over `accountId:devicePassword`, applied via [defaultRequest] when
 * [credentials] is non-null. The `X-Device-ID` header always carries this device's id (not
 * the target device for module reads — that goes in the `device-id` query string).
 *
 * Construction is intentionally simple — one client per (server, account). Switching accounts
 * means building a new client; that aligns with the desktop UX (unlink + relink).
 */
class OctiServerHttpClient(
    private val address: OctiServer.Address,
    val deviceId: DeviceId,
    private val deviceMetadata: DeviceMetadata,
    private val credentials: OctiServer.Credentials? = null,
) : AutoCloseable {

    /**
     * Base URL — `${address.address}/v1`. Every per-call site builds its full URL from this
     * via string concat (the version prefix can't be in defaultRequest's URL because Ktor's
     * merge drops it, see comment in defaultRequest block).
     */
    private val baseUrl: String = "${address.address}/v1"

    private val client: HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Serialization.json)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 60_000
        }
        install(HttpRequestRetry) {
            retryOnExceptionIf(maxRetries = 3) { _, cause -> cause !is kotlinx.coroutines.CancellationException }
            exponentialDelay(base = 2.0, maxDelayMs = 30_000)
            // 429 / 503 with Retry-After is handled per-call where the caller can decide; the
            // generic retry plugin does not parse Retry-After, so we deliberately don't retry
            // on response status here.
        }
        install(WebSockets)

        // TEMP debug: log requests so we can see exactly what URL the WS upgrade hits.
        install(KtorLogging) {
            level = LogLevel.INFO
            logger = object : Logger {
                override fun log(message: String) {
                    log(TAG_HTTP, Logging.Priority.INFO) { message }
                }
            }
        }

        // Non-2xx responses must throw rather than reach .body() — content negotiation would
        // otherwise try to deserialize an empty error body as the expected DTO and surface
        // NoTransformationFoundException, hiding the real status code from callers. With
        // expectSuccess on, Ktor raises ClientRequestException/ServerResponseException; we map
        // those to our OctiServerHttpException via HttpResponseValidator below so upstream code
        // stays Ktor-agnostic.
        expectSuccess = true
        HttpResponseValidator {
            handleResponseExceptionWithRequest { cause, request ->
                if (cause is io.ktor.client.plugins.ResponseException) {
                    throw OctiServerHttpException(
                        status = cause.response.status,
                        url = request.url.toString(),
                    )
                }
            }
        }

        defaultRequest {
            // URL is built explicitly per-call via [baseUrl]. Ktor 3.4's DefaultRequest URL
            // merge drops the path component when applied to per-call requests (verified
            // empirically — both takeFrom-string and component-setter forms lose `/v1`).
            // defaultRequest is kept around purely for the headers that need to ride on every
            // call (X-Device-ID, Octi-Device-*, Authorization).
            headers {
                append(DeviceMetadata.HEADER_DEVICE_ID, deviceId.id)
                append(DeviceMetadata.HEADER_VERSION, deviceMetadata.version)
                append(DeviceMetadata.HEADER_PLATFORM, deviceMetadata.platform)
                append(DeviceMetadata.HEADER_LABEL, deviceMetadata.label)
                if (credentials != null) {
                    @OptIn(ExperimentalEncodingApi::class)
                    val basic = Base64.Default.encode(
                        "${credentials.accountId.id}:${credentials.devicePassword.password}".toByteArray(),
                    )
                    append(HttpHeaders.Authorization, "Basic $basic")
                }
            }
        }
    }

    override fun close() = client.close()

    /**
     * Open a WebSocket session to `/v1/ws` carrying the same Basic auth + Octi-Device-* headers
     * the REST endpoints use (applied via the shared [defaultRequest]).
     *
     * Caller owns the session lifetime — close it when done. Returning the session rather than
     * a `webSocket { ... }` block lets the reconnect state machine in [OctiServerWebSocketClient]
     * use structured cancellation via its scope, instead of nesting inside a Ktor lambda.
     */
    suspend fun openWebSocketSession(): DefaultClientWebSocketSession {
        // Build the WS URL explicitly from the OctiServer.Address rather than relying on the
        // defaultRequest URL merge. Observed bug: when the per-call code switched protocol
        // HTTP → WS via `url.protocol = WS`, Ktor's port for the call fell back to the new
        // protocol's default (80) instead of preserving the explicit 8080 from defaultRequest.
        // Setting host/port/protocol/path-segments directly avoids the merge entirely.
        val wsProtocol = if (address.protocol.equals("https", ignoreCase = true)) {
            io.ktor.http.URLProtocol.WSS
        } else {
            io.ktor.http.URLProtocol.WS
        }
        return client.webSocketSession {
            url {
                protocol = wsProtocol
                host = address.domain
                port = address.port
                appendPathSegments("v1", "ws")
            }
        }
    }

    // --- Account ---

    /** Register a brand-new device. If [shareCode] is non-null, joins an existing account. */
    suspend fun register(shareCode: String? = null): RegisterResponse {
        return client.post {
            url.takeFrom(baseUrl)
            url.appendPathSegments("account")
            if (shareCode != null) url.parameters.append("share", shareCode)
        }.body()
    }

    suspend fun deleteAccount() {
        client.delete { url.takeFrom(baseUrl); url.appendPathSegments("account") }.ensureSuccess()
    }

    suspend fun createShareCode(): ShareCodeResponse =
        client.post { url.takeFrom(baseUrl); url.appendPathSegments("account", "share") }.body()

    suspend fun getAccountStorage(): AccountStorageResponse =
        client.get { url.takeFrom(baseUrl); url.appendPathSegments("account", "storage") }.body()

    // --- Devices ---

    suspend fun getDeviceList(): DevicesResponse =
        client.get { url.takeFrom(baseUrl); url.appendPathSegments("devices") }.body()

    suspend fun resetDevices(request: ResetRequest) {
        client.post {
            url.takeFrom(baseUrl)
            url.appendPathSegments("devices", "reset")
            contentType(ContentType.Application.Json)
            setBody(request)
        }.ensureSuccess()
    }

    suspend fun deleteDevice(target: DeviceId) {
        client.delete { url.takeFrom(baseUrl); url.appendPathSegments("devices", target.id) }.ensureSuccess()
    }

    // --- Module read/write/commit ---

    /**
     * GET /v1/module/{moduleId}?device-id={target}. Returns raw decoded body bytes or
     * [ModuleReadResult.NotFound] for 404 — that's a normal "peer hasn't written this module
     * yet" path, not an error worth throwing.
     *
     * With the client-level `expectSuccess = true`, a 404 normally surfaces as our mapped
     * [OctiServerHttpException]. Catch that here and branch instead.
     */
    suspend fun readModule(moduleId: ModuleId, targetDeviceId: DeviceId): ModuleReadResult {
        return try {
            val response = client.get {
                url.takeFrom(baseUrl)
            url.appendPathSegments("module", moduleId.id)
                url.parameters.append("device-id", targetDeviceId.id)
            }
            ModuleReadResult.Ok(
                payload = response.bodyAsBytes(),
                etag = response.headers[HttpHeaders.ETag],
                modifiedAt = response.headers["X-Modified-At"]?.let { runCatching { Instant.parse(it) }.getOrNull() },
            )
        } catch (e: OctiServerHttpException) {
            if (e.status == HttpStatusCode.NotFound) ModuleReadResult.NotFound else throw e
        }
    }

    /** POST /v1/module/{moduleId}?device-id={self}. Used for legacy raw writes (meta, clipboard). */
    suspend fun writeModule(moduleId: ModuleId, payload: ByteArray) {
        client.post {
            url.takeFrom(baseUrl)
            url.appendPathSegments("module", moduleId.id)
            url.parameters.append("device-id", deviceId.id)
            contentType(ContentType.Application.OctetStream)
            setBody(payload)
        }.ensureSuccess()
    }

    /** PUT /v1/module/{moduleId}?device-id={self}. Used for blob-aware commits (files). */
    suspend fun commitModule(
        moduleId: ModuleId,
        request: ModuleCommitRequest,
        ifMatch: String? = null,
        ifNoneMatch: String? = null,
    ) {
        client.put {
            url.takeFrom(baseUrl)
            url.appendPathSegments("module", moduleId.id)
            url.parameters.append("device-id", deviceId.id)
            contentType(ContentType.Application.Json)
            headers {
                if (ifMatch != null) append(HttpHeaders.IfMatch, ifMatch)
                if (ifNoneMatch != null) append(HttpHeaders.IfNoneMatch, ifNoneMatch)
            }
            setBody(request)
        }.ensureSuccess()
    }

    // --- Blob upload sessions ---

    suspend fun createBlobSession(
        moduleId: ModuleId,
        targetDeviceId: DeviceId,
        request: CreateSessionRequest,
    ): CreateSessionResponse {
        return client.post {
            url.takeFrom(baseUrl)
            url.appendPathSegments("module", moduleId.id, "blob-sessions")
            url.parameters.append("device-id", targetDeviceId.id)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    /**
     * HEAD probe to discover the server's current `Upload-Offset` after a network failure.
     * Required for resilient retry: blind PATCH retry after a timeout can hit 409 because the
     * server already advanced the offset. See plan's "Resilient PATCH Semantics" section.
     */
    suspend fun headBlobSession(
        moduleId: ModuleId,
        sessionId: String,
        targetDeviceId: DeviceId,
    ): SessionStatus {
        val response = client.head {
            url.takeFrom(baseUrl)
            url.appendPathSegments("module", moduleId.id, "blob-sessions", sessionId)
            url.parameters.append("device-id", targetDeviceId.id)
        }
        response.ensureSuccess()
        return SessionStatus(
            sessionId = sessionId,
            uploadOffset = response.headers["Upload-Offset"]?.toLong()
                ?: error("Missing Upload-Offset header"),
            uploadLength = response.headers["Upload-Length"]?.toLong(),
            uploadExpires = response.headers["Upload-Expires"]?.let { runCatching { Instant.parse(it) }.getOrNull() },
            uploadState = response.headers["Upload-State"]
                ?: error("Missing Upload-State header"),
            blobId = response.headers["X-Blob-ID"] ?: error("Missing X-Blob-ID header"),
        )
    }

    /** PATCH one chunk into a session. Caller MUST probe via [headBlobSession] before retry. */
    suspend fun appendBlobChunk(
        moduleId: ModuleId,
        sessionId: String,
        targetDeviceId: DeviceId,
        offset: Long,
        chunk: ByteArray,
    ) {
        client.patch {
            url.takeFrom(baseUrl)
            url.appendPathSegments("module", moduleId.id, "blob-sessions", sessionId)
            url.parameters.append("device-id", targetDeviceId.id)
            headers { append("Upload-Offset", offset.toString()) }
            contentType(ContentType.Application.OctetStream)
            setBody(chunk)
        }.ensureSuccess()
    }

    suspend fun finalizeBlobSession(
        moduleId: ModuleId,
        sessionId: String,
        targetDeviceId: DeviceId,
        request: FinalizeSessionRequest,
    ): FinalizeSessionResponse {
        return client.post {
            url.takeFrom(baseUrl)
            url.appendPathSegments("module", moduleId.id, "blob-sessions", sessionId, "finalize")
            url.parameters.append("device-id", targetDeviceId.id)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    suspend fun abortBlobSession(
        moduleId: ModuleId,
        sessionId: String,
        targetDeviceId: DeviceId,
    ) {
        client.delete {
            url.takeFrom(baseUrl)
            url.appendPathSegments("module", moduleId.id, "blob-sessions", sessionId)
            url.parameters.append("device-id", targetDeviceId.id)
        }.ensureSuccess()
    }

    // --- Blob download / list ---

    suspend fun listBlobs(moduleId: ModuleId, targetDeviceId: DeviceId): BlobListResponse =
        client.get {
            url.takeFrom(baseUrl)
            url.appendPathSegments("module", moduleId.id, "blobs")
            url.parameters.append("device-id", targetDeviceId.id)
        }.body()

    /** Full-file download (no Range — per plan). Caller streams + decrypts + verifies. */
    suspend fun getBlobBytes(
        moduleId: ModuleId,
        blobId: String,
        targetDeviceId: DeviceId,
    ): ByteArray {
        val response = client.get {
            url.takeFrom(baseUrl)
            url.appendPathSegments("module", moduleId.id, "blobs", blobId)
            url.parameters.append("device-id", targetDeviceId.id)
        }
        response.ensureSuccess()
        return response.bodyAsBytes()
    }

    suspend fun deleteBlob(
        moduleId: ModuleId,
        blobId: String,
        targetDeviceId: DeviceId,
        ifMatch: String,
    ) {
        client.delete {
            url.takeFrom(baseUrl)
            url.appendPathSegments("module", moduleId.id, "blobs", blobId)
            url.parameters.append("device-id", targetDeviceId.id)
            headers { append(HttpHeaders.IfMatch, ifMatch) }
        }.ensureSuccess()
    }

    /** Result of [readModule]. Separated so callers can branch on NotFound without try/catch. */
    sealed class ModuleReadResult {
        data class Ok(
            val payload: ByteArray,
            val etag: String?,
            val modifiedAt: Instant?,
        ) : ModuleReadResult() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Ok) return false
                return payload.contentEquals(other.payload) && etag == other.etag && modifiedAt == other.modifiedAt
            }

            override fun hashCode(): Int =
                payload.contentHashCode() * 31 + (etag?.hashCode() ?: 0) * 31 + (modifiedAt?.hashCode() ?: 0)
        }

        data object NotFound : ModuleReadResult()
    }

    private fun HttpResponse.ensureSuccess() {
        if (!status.isSuccess()) {
            log(TAG, Logging.Priority.WARN) { "Non-success status ${status.value} for ${call.request.url}" }
            throw OctiServerHttpException(status, call.request.url.toString())
        }
    }

    companion object {
        private val TAG = logTag("OctiServer", "Http")
        private val TAG_HTTP = logTag("Ktor")
    }
}

class OctiServerHttpException(
    val status: HttpStatusCode,
    val url: String,
) : RuntimeException("OctiServer ${status.value} ${status.description} at $url")

private fun HttpStatusCode.isSuccess() = value in 200..299
