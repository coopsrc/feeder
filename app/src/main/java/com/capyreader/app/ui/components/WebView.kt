package com.capyreader.app.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup.LayoutParams
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import androidx.webkit.WebViewAssetLoader.ResourcesPathHandler
import coil.executeBlocking
import coil.imageLoader
import coil.request.ImageRequest
import com.capyreader.app.R
import com.capyreader.app.common.AppPreferences
import com.capyreader.app.common.WebViewInterface
import com.capyreader.app.common.openLink
import com.capyreader.app.ui.components.LoadingState.Finished
import com.capyreader.app.ui.components.LoadingState.Loading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Doesn't really fetch from androidplatform.net. This is used as a placeholder domain:
 *
 * > Using http(s):// URLs to access local resources may conflict
 * > with a real website. This means that local files should only
 * > be hosted on domains your organization owns (at paths reserved for this purpose)
 * > or the default domain reserved for this: appassets.androidplatform.net
 *
 * * [How-to docs](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content#mix-content)
 * * [JavaDoc](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)
 */
private const val ASSET_BASE_URL = "https://appassets.androidplatform.net"

/**
 * A wrapper around the Android View WebView to provide a basic WebView composable.
 *
 * If you require more customisation you are most likely better rolling your own and using this
 * wrapper as an example.
 *
 * The WebView attempts to set the layoutParams based on the Compose modifier passed in. If it
 * is incorrectly sizing, use the layoutParams composable function instead.
 *
 * @param state The webview state holder where the Uri to load is defined.
 * @param modifier A compose modifier
 * @param captureBackPresses Set to true to have this Composable capture back presses and navigate
 * the WebView back.
 * @param navigator An optional navigator object that can be used to control the WebView's
 * navigation from outside the composable.
 * @param onCreated Called when the WebView is first created, this can be used to set additional
 * settings on the WebView. WebChromeClient and WebViewClient should not be set here as they will be
 * subsequently overwritten after this lambda is called.
 * @param onDispose Called when the WebView is destroyed. Provides a bundle which can be saved
 * if you need to save and restore state in this WebView.
 * @param client Provides access to WebViewClient via subclassing
 * @param chromeClient Provides access to WebChromeClient via subclassing
 * @param factory An optional WebView factory for using a custom subclass of WebView
 * @sample com.google.accompanist.sample.webview.BasicWebViewSample
 */
@Composable
fun WebView(
    state: WebViewState,
    modifier: Modifier = Modifier,
    navigator: WebViewNavigator = rememberWebViewNavigator(),
    onCreated: (WebView) -> Unit = {},
    onDispose: (WebView) -> Unit = {},
    onNavigateToMedia: (url: String) -> Unit = {},
    factory: ((Context) -> WebView)? = null,
) {
    BoxWithConstraints(modifier) {
        // WebView changes it's layout strategy based on
        // it's layoutParams. We convert from Compose Modifier to
        // layout params here.
        val width =
            if (constraints.hasFixedWidth)
                LayoutParams.MATCH_PARENT
            else
                LayoutParams.WRAP_CONTENT
        val height =
            if (constraints.hasFixedHeight)
                LayoutParams.MATCH_PARENT
            else
                LayoutParams.WRAP_CONTENT

        val layoutParams = FrameLayout.LayoutParams(
            width,
            height
        )

        WebView(
            state,
            layoutParams,
            Modifier,
            navigator,
            onCreated,
            onDispose,
            onNavigateToMedia,
            factory
        )
    }
}

/**
 * A wrapper around the Android View WebView to provide a basic WebView composable.
 *
 * If you require more customisation you are most likely better rolling your own and using this
 * wrapper as an example.
 *
 * The WebView attempts to set the layoutParams based on the Compose modifier passed in. If it
 * is incorrectly sizing, use the layoutParams composable function instead.
 *
 * @param state The webview state holder where the Uri to load is defined.
 * @param layoutParams A FrameLayout.LayoutParams object to custom size the underlying WebView.
 * @param modifier A compose modifier
 * @param navigator An optional navigator object that can be used to control the WebView's
 * navigation from outside the composable.
 * @param onCreated Called when the WebView is first created, this can be used to set additional
 * settings on the WebView. WebChromeClient and WebViewClient should not be set here as they will be
 * subsequently overwritten after this lambda is called.
 * @param onDispose Called when the WebView is destroyed. Provides a bundle which can be saved
 * if you need to save and restore state in this WebView.
 * @param client Provides access to WebViewClient via subclassing
 * @param chromeClient Provides access to WebChromeClient via subclassing
 * @param factory An optional WebView factory for using a custom subclass of WebView
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebView(
    state: WebViewState,
    layoutParams: FrameLayout.LayoutParams,
    modifier: Modifier = Modifier,
    navigator: WebViewNavigator = rememberWebViewNavigator(),
    onCreated: (WebView) -> Unit = {},
    onDispose: (WebView) -> Unit = {},
    onNavigateToMedia: (url: String) -> Unit = {},
    factory: ((Context) -> WebView)? = null,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val navigateToMedia = { url: String ->
        coroutineScope.launch(Dispatchers.Main) {
            onNavigateToMedia(url)
        }
    }

    val client = remember {
        AccompanistWebViewClient(
            assetLoader =
            WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .addPathHandler("/assets/", AssetsPathHandler(context))
                .addPathHandler("/res/", ResourcesPathHandler(context))
                .build()
        )
    }
    val chromeClient = remember { AccompanistWebChromeClient() }

    val webView = state.webView

    webView?.let { wv ->
        LaunchedEffect(wv, navigator) {
            with(navigator) {
                wv.handleNavigationEvents()
            }
        }

        LaunchedEffect(wv, state) {
            snapshotFlow { state.content }.collect { content ->
                when (content) {
                    is WebContent.Url -> {
                        wv.loadUrl(content.url, content.additionalHttpHeaders)
                    }

                    is WebContent.Data -> {
                        wv.loadDataWithBaseURL(
                            content.baseUrl,
                            content.data,
                            content.mimeType,
                            content.encoding,
                            content.historyUrl
                        )
                    }

                    is WebContent.Post -> {
                        wv.postUrl(
                            content.url,
                            content.postData
                        )
                    }

                    is WebContent.NavigatorOnly -> {
                        // NO-OP
                    }
                }
            }
        }
    }

    // Set the state of the client and chrome client
    // This is done internally to ensure they always are the same instance as the
    // parent Web composable
    client.state = state
    client.navigator = navigator
    chromeClient.state = state

    AndroidView(
        factory = { ctx ->
            (factory?.invoke(ctx) ?: ctx.inflateWebView()).apply {
                onCreated(this)

                this.settings.javaScriptEnabled = true
                this.settings.mediaPlaybackRequiresUserGesture = false
                isVerticalScrollBarEnabled = false
                this.layoutParams = layoutParams
                addJavascriptInterface(
                    WebViewInterface(
                        navigateToMedia = { navigateToMedia(it) }
                    ),
                    WebViewInterface.INTERFACE_NAME
                )

                setBackgroundColor(ctx.getColor(android.R.color.transparent))

                state.lastScrollY?.let {
                    this.scrollTo(0, it)
                }

                webChromeClient = chromeClient
                webViewClient = client
            }.also { state.webView = it }
        },
        modifier = modifier,
        onRelease = {
            onDispose(it)
        }
    )
}

/**
 * AccompanistWebViewClient
 *
 * A parent class implementation of WebViewClient that can be subclassed to add custom behaviour.
 *
 * As Accompanist Web needs to set its own web client to function, it provides this intermediary
 * class that can be overridden if further custom behaviour is required.
 */
open class AccompanistWebViewClient(private val assetLoader: WebViewAssetLoader) : WebViewClient(),
    KoinComponent {
    open lateinit var state: WebViewState
        internal set
    open lateinit var navigator: WebViewNavigator
        internal set

    private val appPreferences by inject<AppPreferences>()

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        state.loadingState = Loading(0.0f)
        state.errorsForCurrentRequest.clear()
        state.pageTitle = null
        state.pageIcon = null

        state.lastLoadedUrl = url
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        state.loadingState = Finished
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)

        navigator.canGoBack = view.canGoBack()
        navigator.canGoForward = view.canGoForward()
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val accept = request.requestHeaders.getOrDefault("Accept", null)

        if (accept != null && accept.contains("image")) {
            try {
                val imageRequest = ImageRequest.Builder(view.context)
                    .data(request.url)
                    .build()
                val bitmap =
                    view.context.imageLoader.executeBlocking(imageRequest).drawable?.toBitmap()

                if (bitmap != null) {
                    return WebResourceResponse(
                        "image/jpg",
                        "UTF-8",
                        bitmapInputStream(bitmap, Bitmap.CompressFormat.JPEG)
                    )
                }
            } catch (exception: Exception) {
                return null
            }
        }

        return assetLoader.shouldInterceptRequest(request.url)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url

        if (view != null && url != null) {
            view.context.openLink(url, appPreferences)
        }

        return true
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)

        if (error != null) {
            state.errorsForCurrentRequest.add(WebViewError(request, error))
        }
    }
}

/**
 * AccompanistWebChromeClient
 *
 * A parent class implementation of WebChromeClient that can be subclassed to add custom behaviour.
 *
 * As Accompanist Web needs to set its own web client to function, it provides this intermediary
 * class that can be overridden if further custom behaviour is required.
 */
open class AccompanistWebChromeClient : WebChromeClient() {
    open lateinit var state: WebViewState
        internal set

    override fun onReceivedTitle(view: WebView, title: String?) {
        super.onReceivedTitle(view, title)
        state.pageTitle = title
    }

    override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
        super.onReceivedIcon(view, icon)
        state.pageIcon = icon
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        if (state.loadingState is Finished) return
        state.loadingState = Loading(newProgress / 100.0f)
    }
}

sealed class WebContent {
    data class Url(
        val url: String,
        val additionalHttpHeaders: Map<String, String> = emptyMap(),
    ) : WebContent()

    data class Data(
        val data: String,
        val baseUrl: String? = null,
        val encoding: String = "utf-8",
        val mimeType: String? = null,
        val historyUrl: String? = null
    ) : WebContent()

    data class Post(
        val url: String,
        val postData: ByteArray
    ) : WebContent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Post

            if (url != other.url) return false

            return postData.contentEquals(other.postData)
        }

        override fun hashCode(): Int {
            var result = url.hashCode()
            result = 31 * result + postData.contentHashCode()
            return result
        }
    }

    data object NavigatorOnly : WebContent()
}

internal fun WebContent.withUrl(url: String) = when (this) {
    is WebContent.Url -> copy(url = url)
    else -> WebContent.Url(url)
}

/**
 * Sealed class for constraining possible loading states.
 * See [Loading] and [Finished].
 */
sealed class LoadingState {
    /**
     * Describes a WebView that has not yet loaded for the first time.
     */
    data object Initializing : LoadingState()

    /**
     * Describes a webview between `onPageStarted` and `onPageFinished` events, contains a
     * [progress] property which is updated by the webview.
     */
    data class Loading(val progress: Float) : LoadingState()

    /**
     * Describes a webview that has finished loading content.
     */
    data object Finished : LoadingState()
}

/**
 * A state holder to hold the state for the WebView. In most cases this will be remembered
 * using the rememberWebViewState(uri) function.
 */
@Stable
class WebViewState(webContent: WebContent) {
    var lastLoadedUrl: String? by mutableStateOf(null)
        internal set

    /**
     *  The content being loaded by the WebView
     */
    var content: WebContent by mutableStateOf(webContent)

    /**
     * Whether the WebView is currently [LoadingState.Loading] data in its main frame (along with
     * progress) or the data loading has [LoadingState.Finished]. See [LoadingState]
     */
    public var loadingState: LoadingState by mutableStateOf(LoadingState.Initializing)
        internal set

    /**
     * Whether the webview is currently loading data in its main frame
     */
    val isLoading: Boolean
        get() = loadingState !is Finished

    /**
     * The title received from the loaded content of the current page
     */
    var pageTitle: String? by mutableStateOf(null)
        internal set

    /**
     * the favicon received from the loaded content of the current page
     */
    var pageIcon: Bitmap? by mutableStateOf(null)
        internal set

    /**
     * A list for errors captured in the last load. Reset when a new page is loaded.
     * Errors could be from any resource (iframe, image, etc.), not just for the main page.
     * For more fine grained control use the OnError callback of the WebView.
     */
    val errorsForCurrentRequest: SnapshotStateList<WebViewError> = mutableStateListOf()

    var lastScrollY: Int? = null

    // We need access to this in the state saver. An internal DisposableEffect or AndroidView
    // onDestroy is called after the state saver and so can't be used.
    internal var webView by mutableStateOf<WebView?>(null)
}

/**
 * Allows control over the navigation of a WebView from outside the composable. E.g. for performing
 * a back navigation in response to the user clicking the "up" button in a TopAppBar.
 *
 * @see [rememberWebViewNavigator]
 */
@Stable
public class WebViewNavigator(private val coroutineScope: CoroutineScope) {
    private sealed interface NavigationEvent {
        data object Back : NavigationEvent
        data object Forward : NavigationEvent
        data object Reload : NavigationEvent
        data object StopLoading : NavigationEvent

        data object ClearView : NavigationEvent

        data class LoadUrl(
            val url: String,
            val additionalHttpHeaders: Map<String, String> = emptyMap()
        ) : NavigationEvent

        data class LoadHtml(
            val html: String,
            val mimeType: String? = null,
            val encoding: String? = "utf-8",
            val historyUrl: String? = null
        ) : NavigationEvent

        data class PostUrl(
            val url: String,
            val postData: ByteArray
        ) : NavigationEvent {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as PostUrl

                if (url != other.url) return false
                return postData.contentEquals(other.postData)
            }

            override fun hashCode(): Int {
                var result = url.hashCode()
                result = 31 * result + postData.contentHashCode()
                return result
            }
        }
    }

    private val navigationEvents: MutableSharedFlow<NavigationEvent> = MutableSharedFlow(replay = 1)

    // Use Dispatchers.Main to ensure that the webview methods are called on UI thread
    @OptIn(ExperimentalCoroutinesApi::class)
    internal suspend fun WebView.handleNavigationEvents(): Nothing = withContext(Dispatchers.Main) {
        navigationEvents.collect { event ->
            when (event) {
                is NavigationEvent.Back -> {
                    goBack()
                }

                is NavigationEvent.Forward -> goForward()
                is NavigationEvent.Reload -> reload()
                is NavigationEvent.StopLoading -> stopLoading()
                is NavigationEvent.LoadHtml -> {
                    loadDataWithBaseURL(
                        ASSET_BASE_URL,
                        event.html,
                        event.mimeType,
                        event.encoding,
                        event.historyUrl
                    )
                    isVerticalScrollBarEnabled = true
                }

                is NavigationEvent.ClearView -> {
                    navigationEvents.resetReplayCache()
                    clearHistory()
                    loadUrl("about:blank")
                }

                is NavigationEvent.LoadUrl -> {
                    loadUrl(event.url, event.additionalHttpHeaders)
                }

                is NavigationEvent.PostUrl -> {
                    postUrl(event.url, event.postData)
                }
            }
        }
    }

    /**
     * True when the web view is able to navigate backwards, false otherwise.
     */
    var canGoBack: Boolean by mutableStateOf(false)
        internal set

    /**
     * True when the web view is able to navigate forwards, false otherwise.
     */
    var canGoForward: Boolean by mutableStateOf(false)
        internal set

    fun loadUrl(url: String, additionalHttpHeaders: Map<String, String> = emptyMap()) {
        coroutineScope.launch {
            navigationEvents.emit(
                NavigationEvent.LoadUrl(
                    url,
                    additionalHttpHeaders
                )
            )
        }
    }

    fun loadHtml(
        html: String,
        mimeType: String? = null,
        encoding: String? = "utf-8",
        historyUrl: String? = null
    ) {
        coroutineScope.launch {
            navigationEvents.emit(
                NavigationEvent.LoadHtml(
                    html,
                    mimeType,
                    encoding,
                    historyUrl
                )
            )
        }
    }

    fun clearView() {
        coroutineScope.launch {
            navigationEvents.emit(
                NavigationEvent.ClearView
            )
        }
    }

    fun postUrl(
        url: String,
        postData: ByteArray
    ) {
        coroutineScope.launch {
            navigationEvents.emit(
                NavigationEvent.PostUrl(
                    url,
                    postData
                )
            )
        }
    }

    /**
     * Navigates the webview back to the previous page.
     */
    fun navigateBack() {
        coroutineScope.launch { navigationEvents.emit(NavigationEvent.Back) }
    }

    /**
     * Navigates the webview forward after going back from a page.
     */
    public fun navigateForward() {
        coroutineScope.launch { navigationEvents.emit(NavigationEvent.Forward) }
    }

    /**
     * Reloads the current page in the webview.
     */
    public fun reload() {
        coroutineScope.launch { navigationEvents.emit(NavigationEvent.Reload) }
    }

    /**
     * Stops the current page load (if one is loading).
     */
    public fun stopLoading() {
        coroutineScope.launch { navigationEvents.emit(NavigationEvent.StopLoading) }
    }
}

/**
 * Creates and remembers a [WebViewNavigator] using the default [CoroutineScope] or a provided
 * override.
 */
@Composable
fun rememberWebViewNavigator(
    coroutineScope: CoroutineScope = rememberCoroutineScope()
): WebViewNavigator = remember(coroutineScope) { WebViewNavigator(coroutineScope) }

/**
 * A wrapper class to hold errors from the WebView.
 */
@Immutable
public data class WebViewError(
    /**
     * The request the error came from.
     */
    val request: WebResourceRequest?,
    /**
     * The error that was reported.
     */
    val error: WebResourceError
)

/**
 * Creates a WebView state that is remembered across Compositions and saved
 * across activity recreation.
 * When using saved state, you cannot change the URL via recomposition. The only way to load
 * a URL is via a WebViewNavigator.
 *
 * @param data The uri to load in the WebView
 * @sample com.google.accompanist.sample.webview.WebViewSaveStateSample
 */
@Composable
fun rememberSaveableWebViewState(key: String?): WebViewState =
    rememberSaveable(saver = WebStateSaver, key = key) {
        WebViewState(WebContent.NavigatorOnly)
    }

val WebStateSaver: Saver<WebViewState, Any> = run {
    val scrollY = "scrollY"

    mapSaver(
        save = {
            val y = it.webView?.scrollY ?: 0
            mapOf(
                scrollY to y
            )
        },
        restore = {
            WebViewState(WebContent.NavigatorOnly).apply {
                val y = it[scrollY] as Int?

                lastScrollY = y
            }
        },
    )
}

private fun Context.inflateWebView(): WebView {
    return LayoutInflater
        .from(this)
        .inflate(R.layout.article_webview, null, false) as WebView
}

private fun bitmapInputStream(
    bitmap: Bitmap,
    compressFormat: Bitmap.CompressFormat
): InputStream {
    val byteArrayOutputStream = ByteArrayOutputStream()
    bitmap.compress(compressFormat, 100, byteArrayOutputStream)
    val bitmapData = byteArrayOutputStream.toByteArray()
    return ByteArrayInputStream(bitmapData)
}
