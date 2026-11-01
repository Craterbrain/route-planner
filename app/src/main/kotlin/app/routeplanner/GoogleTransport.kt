package app.routeplanner

import android.content.Context
import android.util.Log
import app.routeplanner.core.HttpGet
import app.routeplanner.core.HttpStatusException
import app.routeplanner.core.TimedGet
import app.routeplanner.core.google.GoogleWeb
import com.google.android.gms.net.CronetProviderInstaller
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.CronetProvider
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * HTTP for the Google suggestion endpoint only. Uses Chromium's network stack
 * (Cronet) from Google Play services when the phone has it, so the connection
 * behaves like the Chrome version the headers claim; otherwise a plain
 * HttpURLConnection with the fallback Chrome major. Started lazily; any
 * failure (no Play services, provider missing, native load error) just keeps
 * the plain client. Never logs URLs or the typed text.
 */
object GoogleTransport {
    private const val TIMEOUT_MS = 6_000L

    @Volatile private var engine: CronetEngine? = null
    @Volatile private var started = false
    @Volatile private var cronetMajor: Int? = null
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "google-cronet").apply { isDaemon = true } }
    private val plain = TimedGet(TIMEOUT_MS.toInt(), TIMEOUT_MS.toInt())

    /** "Cronet 141 (Play services)" / "plain HTTP": for Settings. */
    val description: String get() = cronetMajor?.let { "Chromium network stack $it (Google Play services)" } ?: "plain HTTP (Play services Cronet not available)"

    /** Chrome major to claim in User-Agent / Sec-CH-UA: Cronet's own when it carries the request. */
    val chromeMajor: Int get() = cronetMajor ?: GoogleWeb.FALLBACK_CHROME_MAJOR

    /** Asks Play services to install its Cronet provider; the engine is built when that succeeds. */
    @Synchronized fun warmUp(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        runCatching {
            CronetProviderInstaller.installProvider(app).addOnCompleteListener { task ->
                if (!task.isSuccessful) { AppLog.i("google: Play services Cronet unavailable (${task.exception?.javaClass?.simpleName}); plain HTTP"); return@addOnCompleteListener }
                runCatching { buildEngine(app) }.onFailure { AppLog.w("google: Cronet engine failed: $it; plain HTTP") }
            }
        }.onFailure { AppLog.i("google: no Play services Cronet ($it); plain HTTP") }
    }

    private fun buildEngine(ctx: Context) {
        val provider = CronetProvider.getAllProviders(ctx).firstOrNull { it.name == CronetProviderInstaller.PROVIDER_NAME && it.isEnabled }
            ?: run { AppLog.i("google: Play services Cronet provider not enabled; plain HTTP"); return }
        val e = provider.createBuilder()
            .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISABLED, 0)
            .enableHttp2(true)
            .enableQuic(true)
            .build()
        val major = GoogleWeb.majorFrom(e.versionString)
        if (major == null) { e.shutdown(); AppLog.w("google: unknown Cronet version; plain HTTP"); return }
        engine = e
        cronetMajor = major
        AppLog.i("google: using Cronet $major")
    }

    /**
     * Transport for one consumer. With [cancelPrevious] a new request cancels the
     * one still in flight (typing: only the newest text matters).
     */
    fun http(ctx: Context, cancelPrevious: Boolean): HttpGet {
        warmUp(ctx)
        return object : HttpGet {
            @Volatile private var inflight: UrlRequest? = null
            override fun get(url: String, headers: Map<String, String>): String {
                val e = engine ?: return plain.get(url, headers)
                return try {
                    cronetGet(e, url, headers) { req -> if (cancelPrevious) { inflight?.cancel(); inflight = req } }
                } catch (t: UnsatisfiedLinkError) {
                    engine = null; cronetMajor = null
                    plain.get(url, headers)
                }
            }
        }
    }

    private fun cronetGet(e: CronetEngine, url: String, headers: Map<String, String>, onStart: (UrlRequest) -> Unit): String {
        val done = CountDownLatch(1)
        val body = ByteArrayOutputStream()
        var status = 0
        var error: IOException? = null
        val cb = object : UrlRequest.Callback() {
            override fun onRedirectReceived(r: UrlRequest, info: UrlResponseInfo, newUrl: String) { r.followRedirect() }
            override fun onResponseStarted(r: UrlRequest, info: UrlResponseInfo) { status = info.httpStatusCode; r.read(ByteBuffer.allocateDirect(32 * 1024)) }
            override fun onReadCompleted(r: UrlRequest, info: UrlResponseInfo, buf: ByteBuffer) {
                buf.flip()
                val bytes = ByteArray(buf.remaining()); buf.get(bytes); body.write(bytes)
                buf.clear(); r.read(buf)
            }
            override fun onSucceeded(r: UrlRequest, info: UrlResponseInfo) { done.countDown() }
            override fun onFailed(r: UrlRequest, info: UrlResponseInfo?, ex: CronetException) { error = IOException("cronet: ${ex.javaClass.simpleName}"); done.countDown() }
            override fun onCanceled(r: UrlRequest, info: UrlResponseInfo?) { error = IOException("canceled"); done.countDown() }
        }
        val b = e.newUrlRequestBuilder(url, cb, executor).disableCache()
        headers.forEach { (k, v) -> b.addHeader(k, v) }
        val req = b.build()
        onStart(req)
        req.start()
        if (!done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { req.cancel(); throw SocketTimeoutException("google: timed out") }
        error?.let { throw it }
        if (status !in 200..299) throw HttpStatusException(status)
        return body.toString(Charsets.UTF_8.name())
    }
}
