package io.homeassistant.companion.android.webview

import android.os.Bundle
import io.homeassistant.companion.android.BaseActivity
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.util.FailFast
import io.homeassistant.companion.android.frontend.navigation.FrontendTarget
import io.homeassistant.companion.android.launch.startLaunchWithNavigateTo

private const val EXTRA_PATH = "path"
private const val EXTRA_SERVER = "server"

@Deprecated(
    "WebViewActivity must not be used; kept only to redirect legacy shortcuts.",
    level = DeprecationLevel.ERROR,
)
class WebViewActivity : BaseActivity() {

        private const val APP_PREFIX = "app://"
        private const val INTENT_PREFIX = "intent:"
        private const val MARKET_PREFIX = "https://play.google.com/store/apps/details?id="

        fun newInstance(context: Context, path: String? = null, serverId: Int? = null): Intent {
            return Intent(context, WebViewActivity::class.java).apply {
                putExtra(EXTRA_PATH, path)
                putExtra(EXTRA_SERVER, serverId)
            }
        }

        private const val CONNECTION_DELAY = 10000L
    }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (it.any { result -> result.value }) {
                webView.reload()
            }
        }
    private val requestStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                downloadFile(downloadFileUrl, downloadFileContentDisposition, downloadFileMimetype)
            }
        }
    private val requestImprovPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                presenter.startScanningForImprov()
            }
        }
    private val writeNfcTag = registerForActivityResult(WriteNfcTag()) { messageId ->
        sendExternalBusMessage(
            ExternalBusMessage(
                id = messageId,
                type = "result",
                success = true,
                result = emptyMap<String, String>(),
                callback = {
                    Timber.d("NFC Write Complete $it")
                },
            ),
        )
    }
    private val showWebFileChooser = registerForActivityResult(ShowWebFileChooser()) { result ->
        mFilePathCallback?.onReceiveValue(result)
        mFilePathCallback = null
    }
    private val commissionMatterDevice =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            presenter.onMatterThreadIntentResult(this, result)
        }

    @Inject
    lateinit var presenter: WebViewPresenter

    @Inject
    lateinit var nightModeManager: NightModeManager

    @Inject
    lateinit var changeLog: ChangeLog

    @Inject
    lateinit var serverManager: ServerManager

    @Inject
    lateinit var authenticationDao: AuthenticationDao

    @Inject
    @NamedKeyChain
    lateinit var keyChainRepository: KeyChainRepository

    @Inject
    lateinit var appVersionProvider: AppVersionProvider

    @Inject
    lateinit var entityAddToHandler: EntityAddToHandler

    @Inject
    lateinit var dataUriDownloadManager: DataUriDownloadManager

    @Inject
    lateinit var dataSourceFactory: DataSource.Factory

    private lateinit var webView: WebView

    // Reference to the WebViewClient instance bound to [webView]. Kept alongside the
    // WebView itself because WebView.getWebViewClient() is only available from API 26,
    // while the app's minimum SDK is 23.
    private var webViewTlsClient: TLSWebViewClient? = null
    private var loadedUrl: Uri? = null
    private lateinit var decor: FrameLayout
    private var customViewFromWebView = mutableStateOf<View?>(null)
    private lateinit var authenticator: Authenticator

    private lateinit var windowInsetsController: WindowInsetsControllerCompat

    private var mFilePathCallback: ValueCallback<Array<Uri>>? = null
    private var isConnected = false
    private var isShowingError = false
    private var isRelaunching = false
    private var alertDialog: AlertDialog? = null
    private var loadUrlJob: Job? = null
    private var isVideoFullScreen = false
    private var videoHeight = 0
    private var firstAuthTime: Long = 0
    private var resourceURL: String = ""
    private var appLocked = mutableStateOf(true)
    private var unlockingApp = false
    private var exoPlayer = mutableStateOf<ExoPlayer?>(null)
    private var isExoFullScreen = false
    private var playerSize = mutableStateOf<DpSize?>(null)
    private var playerTop = mutableStateOf(0.dp)
    private var playerLeft = mutableStateOf(0.dp)
    private var statusBarColor = mutableStateOf<Color?>(null)
    private var backgroundColor = mutableStateOf<Color?>(null)

    /**
     * Flag to know when the webview has been fully initialized (loadUrl called).
     * It is important to know to avoid opening a full screen dialog that
     * could prevent the loading of the webview.
     */
    private var webViewInitialized = mutableStateOf(false)
    private var failedConnection = "external"

    private var clearHistory = false

    /**
     * Optional override for the internal/external URL selection logic.
     *
     * When set, this function is passed to [WebViewPresenter.load] to override the automatic
     * internal/external URL detection. This is used when the user explicitly requests to refresh
     * using the internal or external URL from the error dialog (e.g., after a connection failure).
     *
     * The override persists for the activity's lifecycle so that subsequent loads
     * continue to use the user's preference.
     *
     * Keep in mind that it only applies to the Webview not for anything else like
     * background sync, widgets, ... It is used as a temporary fix for the user to
     * access their server, but something might need to be fixed on user side.
     *
     * Defaults to `null`, meaning automatic URL selection is used.
     */
    private var isInternalOverride: ((ServerConnectionInfo) -> Boolean)? = null
    private var moreInfoEntity = ""
    private val moreInfoMutex = Mutex()
    private var currentAutoplay: Boolean? = null
    private var downloadFileUrl = ""
    private var downloadFileContentDisposition = ""
    private var downloadFileMimetype = ""
    private var serverHandleInsets = mutableStateOf(false)

    private val snackbarHostState = SnackbarHostState()

    private data class InsetsContext(
        val windowInsets: WindowInsets,
        val density: Density,
        val displayMetrics: DisplayMetrics,
        val layoutDirection: LayoutDirection,
    ) {
        fun applyInsets(webView: WebView) {
            webView.applyInsets(windowInsets, density, displayMetrics, layoutDirection)
        }
    }

    private var insetsContext: InsetsContext? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        FailFast.fail { "WebViewActivity must not be used anymore." }
        super.onCreate(savedInstanceState)

        if (intent.extras?.containsKey(EXTRA_SERVER) == true) {
            intent.extras?.getInt(EXTRA_SERVER)?.let {
                lifecycleScope.launch {
                    presenter.setActiveServer(it)
                }
                intent.removeExtra(EXTRA_SERVER)
            }
        }

        windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)

        // Initially set status and navigation bar color to colorLaunchScreenBackground to match the launch screen until the web frontend is loaded
        val colorLaunchScreenBackground = ResourcesCompat.getColor(
            resources,
            commonR.color.colorLaunchScreenBackground,
            theme,
        )
        setStatusBarAndBackgroundColor(colorLaunchScreenBackground, colorLaunchScreenBackground)

        webView = WebView(this)

        lifecycleScope.launch {
            appLocked.value = presenter.isAppLocked()
        }

        setContent {
            val coroutineScope = rememberCoroutineScope()
            val player by remember { exoPlayer }
            val playerSize by remember { playerSize }
            val playerTop by remember { playerTop }
            val playerLeft by remember { playerLeft }
            val currentAppLocked by remember { appLocked }
            val customViewFromWebView by remember { customViewFromWebView }
            val statusBarColor by remember { statusBarColor }
            val backgroundColor by remember { backgroundColor }
            val serverHandleInsets by remember { serverHandleInsets }
            var nightModeTheme by remember { mutableStateOf<NightModeTheme?>(null) }
            val snackbarHostState = remember { snackbarHostState }
            var webViewInitialized by remember { webViewInitialized }
            var shouldAskNotificationPermission by remember { mutableStateOf(false) }

            val configuration = LocalConfiguration.current
            val currentInsetsContext = InsetsContext(
                windowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
                density = LocalDensity.current,
                displayMetrics = LocalResources.current.displayMetrics,
                layoutDirection = LocalLayoutDirection.current,
            )
            insetsContext = currentInsetsContext

            // Apply insets when configuration changes (e.g., screen rotation)
            LaunchedEffect(configuration, serverHandleInsets) {
                if (serverHandleInsets) {
                    currentInsetsContext.applyInsets(webView)
                }
            }

            LaunchedEffect(Unit) {
                nightModeTheme = nightModeManager.getCurrentNightMode()
                shouldAskNotificationPermission = presenter.shouldAskNotificationPermission()
            }

            WebViewContentScreen(
                webView,
                player,
                snackbarHostState = snackbarHostState,
                playerSize = playerSize,
                playerTop = playerTop,
                playerLeft = playerLeft,
                currentAppLocked,
                customViewFromWebView,
                shouldAskNotificationPermission = shouldAskNotificationPermission,
                webViewInitialized = webViewInitialized,
                serverHandleInsets = serverHandleInsets,
                nightModeTheme = nightModeTheme,
                statusBarColor = statusBarColor,
                backgroundColor = backgroundColor,
                onFullscreenClicked = { isFullScreen ->
                    isExoFullScreen = isFullScreen
                    if (isFullScreen) hideSystemUI() else showSystemUI()
                },
                onNotificationPermissionResult = { granted ->
                    coroutineScope.launch {
                        presenter.onNotificationPermissionResult(granted)
                        shouldAskNotificationPermission = false
                    }
                },
            )
        }

        authenticator = Authenticator(this, this, ::authenticationResult)

        decor = window.decorView as FrameLayout

        val onBackPressed = object : OnBackPressedCallback(webView.canGoBack()) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
            }
        }

        onBackPressedDispatcher.addCallback(this, onBackPressed)

        webView.apply {
            setOnTouchListener(
                object : OnSwipeListener(this@WebViewActivity) {
                    override fun onSwipe(
                        e1: MotionEvent,
                        e2: MotionEvent,
                        velocity: Float,
                        direction: GestureDirection,
                        pointerCount: Int,
                    ): Boolean {
                        if (pointerCount > 1 && velocity >= 75 && !appLocked.value) {
                            handleWebViewGesture(direction, pointerCount)
                        }
                        // Swipe as a gesture is handled async. Irregardless of the result, and to
                        // not block, we don't consume it and allow other views to respond to it.
                        // (Except if the app is locked, but that realistically shouldn't happen
                        // as the locked blur should prevent the WebView from getting swipes.)
                        return appLocked.value
                    }

                    override fun onMotionEventHandled(v: View?, event: MotionEvent?): Boolean {
                        return appLocked.value
                    }
                },
            )

            lifecycleScope.launch {
                currentAutoplay = presenter.isAutoPlayVideoEnabled().apply {
                    settings.mediaPlaybackRequiresUserGesture = !this
                }
            }

            val tlsClient = object : TLSWebViewClient(keyChainRepository) {
                @Deprecated("Deprecated in Java for SDK >= 23")
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?,
                ) {
                    Timber.e("onReceivedError: errorCode: $errorCode url:$failingUrl")
                    if (failingUrl == loadedUrl.toString()) {
                        showError()
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    webViewInitialized.value = true
                    if (clearHistory) {
                        webView.clearHistory()
                        clearHistory = false
                    }

                    if (serverHandleInsets.value) {
                        insetsContext?.applyInsets(webView)
                    }

                    setWebViewZoom()
                    if (moreInfoEntity != "" && view?.progress == 100 && isConnected) {
                        lifecycleScope.launch {
                            val owner = "onPageFinished:$moreInfoEntity"
                            if (moreInfoMutex.tryLock(owner)) {
                                delay(2000L)
                                Timber.d("More info entity: $moreInfoEntity")
                                webView.evaluateJavascript(
                                    "document.querySelector(\"home-assistant\").dispatchEvent(new CustomEvent(\"hass-more-info\", { detail: { entityId: \"$moreInfoEntity\" }}))",
                                ) {
                                    moreInfoMutex.unlock(owner)
                                    moreInfoEntity = ""
                                }
                            }
                        }
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    Timber.e(
                        "onReceivedHttpError: ${errorResponse?.statusCode} : ${errorResponse?.reasonPhrase} for: ${request?.url}",
                    )
                    if (request?.url == loadedUrl) {
                        showError()
                    }
                }

                override fun onReceivedHttpAuthRequest(
                    view: WebView,
                    handler: HttpAuthHandler,
                    host: String,
                    realm: String,
                ) {
                    var authError = false
                    if (System.currentTimeMillis() <= (firstAuthTime + 500)) {
                        authError = true
                    }
                    authenticationDialog(handler, host, resourceURL, realm, authError)
                }

                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    Timber.e("onReceivedSslError: $error")
                    showError(
                        ErrorType.SSL,
                        error,
                        null,
                    )
                }

                override fun onRenderProcessGone(view: WebView?, handler: RenderProcessGoneDetail?): Boolean {
                    Timber.e("onRenderProcessGone: webView crashed")
                    view?.let {
                        reload()
                        lifecycleScope.launch {
                            webViewAddJavascriptInterface()
                        }
                    }

                    return true
                }

                override fun onLoadResource(view: WebView?, url: String?) {
                    resourceURL = url!!
                }

                // Override deprecated method for backward compatibility with API 23 and below.
                // The non-deprecated shouldOverrideUrlLoading(WebView, WebResourceRequest) is not invoked
                // on these older Android versions, so this method remains necessary.
                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    url?.let {
                        try {
                            if (it.startsWith(APP_PREFIX)) {
                                Timber.d("Launching the app")
                                val intent = packageManager.getLaunchIntentForPackage(
                                    it.substringAfter(APP_PREFIX),
                                )
                                if (intent != null) {
                                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    startActivity(intent)
                                } else {
                                    Timber.w("No intent to launch app found, opening app store")
                                    val marketIntent = Intent(Intent.ACTION_VIEW)
                                    marketIntent.data =
                                        (MARKET_PREFIX + it.substringAfter(APP_PREFIX)).toUri()
                                    startActivity(marketIntent)
                                }
                                return true
                            } else if (it.startsWith(INTENT_PREFIX)) {
                                Timber.d("Launching the intent")
                                val intent =
                                    Intent.parseUri(it, Intent.URI_INTENT_SCHEME)
                                val intentPackage = intent.`package`?.let { it1 ->
                                    packageManager.getLaunchIntentForPackage(
                                        it1,
                                    )
                                }
                                if (intentPackage == null && !intent.`package`.isNullOrEmpty()) {
                                    Timber.w("No app found for intent prefix, opening app store")
                                    val marketIntent = Intent(Intent.ACTION_VIEW)
                                    marketIntent.data =
                                        (MARKET_PREFIX + intent.`package`.toString()).toUri()
                                    startActivity(marketIntent)
                                } else {
                                    startActivity(intent)
                                }
                                return true
                            } else if (!webView.url.toString().contains(it)) {
                                Timber.d("Launching browser")
                                val browserIntent = Intent(Intent.ACTION_VIEW, it.toUri())
                                startActivity(browserIntent)
                                return true
                            } else {
                                // Do nothing.
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Unable to override the URL")
                        }
                    }
                    return false
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    onBackPressed.isEnabled = canGoBack()
                    presenter.stopScanningForImprov(false)
                }
            }
            webViewClient = tlsClient
            this@WebViewActivity.webViewTlsClient = tlsClient

            setDownloadListener { url, _, contentDisposition, mimetype, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                    ActivityCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    downloadFile(url, contentDisposition, mimetype)
                } else {
                    downloadFileUrl = url
                    downloadFileContentDisposition = contentDisposition
                    downloadFileMimetype = mimetype
                    requestStoragePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                    AlertDialog
                        .Builder(this@WebViewActivity)
                        .setTitle(commonR.string.app_name)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                        .setOnDismissListener { result.cancel() }
                        .create()
                        .show()
                    return true
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    val alreadyGranted = ArrayList<String>()
                    val toBeGranted = ArrayList<String>()
                    request?.resources?.forEach {
                        if (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE) {
                            if (ActivityCompat.checkSelfPermission(
                                    context,
                                    android.Manifest.permission.CAMERA,
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                alreadyGranted.add(it)
                            } else {
                                toBeGranted.add(android.Manifest.permission.CAMERA)
                            }
                        } else if (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE) {
                            if (ActivityCompat.checkSelfPermission(
                                    context,
                                    android.Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                alreadyGranted.add(it)
                            } else {
                                toBeGranted.add(android.Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }
                    if (alreadyGranted.isNotEmpty()) {
                        request?.grant(alreadyGranted.toTypedArray())
                    }
                    if (toBeGranted.isNotEmpty()) {
                        requestPermissions.launch(
                            toBeGranted.toTypedArray(),
                        )
                    }
                }

                override fun onShowFileChooser(
                    view: WebView,
                    uploadMsg: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams,
                ): Boolean {
                    mFilePathCallback = uploadMsg
                    showWebFileChooser.launch(fileChooserParams)
                    return true
                }

                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    customViewFromWebView.value = view
                    hideSystemUI()
                    isVideoFullScreen = true
                }

                override fun onHideCustomView() {
                    customViewFromWebView.value = null
                    lifecycleScope.launch {
                        if (!presenter.isFullScreen()) {
                            showSystemUI()
                        }
                    }
                    isVideoFullScreen = false
                    super.onHideCustomView()
                }
            }
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                lifecycleScope.launch {
                    if (presenter.isFullScreen()) {
                        hideSystemUI()
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val webviewPackage = WebViewCompat.getCurrentWebViewPackage(this)
            Timber.d(
                "Current webview package ${webviewPackage?.packageName} and version ${webviewPackage?.versionName}",
            )
        }

        lifecycleScope.launch {
            if (presenter.isKeepScreenOnEnabled()) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                presenter.getMatterThreadStepFlow().collect {
                    Timber.d("Matter/Thread step changed to $it")
                    when (it) {
                        MatterThreadStep.THREAD_EXPORT_TO_SERVER_MATTER,
                        MatterThreadStep.THREAD_EXPORT_TO_SERVER_ONLY,
                        MatterThreadStep.MATTER_IN_PROGRESS,
                        -> {
                            presenter.getMatterThreadIntent()?.let { intentSender ->
                                commissionMatterDevice.launch(IntentSenderRequest.Builder(intentSender).build())
                            }
                        }

                        MatterThreadStep.THREAD_NONE -> {
                            alertDialog?.cancel()
                            AlertDialog.Builder(this@WebViewActivity)
                                .setMessage(commonR.string.thread_export_none)
                                .setPositiveButton(commonR.string.ok, null)
                                .show()
                            presenter.finishMatterThreadFlow()
                        }

                        MatterThreadStep.THREAD_SENT -> {
                            launch {
                                snackbarHostState.showSnackbar(getString(commonR.string.thread_export_success))
                            }
                            alertDialog?.cancel()
                            presenter.finishMatterThreadFlow()
                        }

                        MatterThreadStep.ERROR_MATTER_CANCELLED,
                        MatterThreadStep.ERROR_MATTER_OTHER,
                        MatterThreadStep.ERROR_THREAD_OTHER,
                        -> {
                            val message = when (it) {
                                MatterThreadStep.ERROR_MATTER_CANCELLED ->
                                    commonR.string.matter_commissioning_cancelled

                                MatterThreadStep.ERROR_MATTER_OTHER ->
                                    commonR.string.matter_commissioning_unavailable

                                MatterThreadStep.ERROR_THREAD_OTHER ->
                                    commonR.string.thread_export_unavailable
                            }
                            val uri = when (it) {
                                MatterThreadStep.ERROR_MATTER_CANCELLED ->
                                    "https://www.home-assistant.io/integrations/matter#troubleshooting"

                                MatterThreadStep.ERROR_MATTER_OTHER,
                                MatterThreadStep.ERROR_THREAD_OTHER,
                                ->
                                    "https://www.home-assistant.io/integrations/matter#troubleshooting-the-installation"
                            }
                            launch {
                                if (snackbarHostState.showSnackbar(
                                        message = getString(message),
                                        actionLabel = getString(commonR.string.get_help),
                                        duration = SnackbarDuration.Long,
                                    ) == SnackbarResult.ActionPerformed
                                ) {
                                    val intent = Intent(Intent.ACTION_VIEW, uri.toUri())
                                    startActivity(intent)
                                }
                            }
                            alertDialog?.cancel()
                            presenter.finishMatterThreadFlow()
                        }

                        MatterThreadStep.ERROR_THREAD_LOCAL_NETWORK -> {
                            alertDialog?.cancel()
                            AlertDialog.Builder(this@WebViewActivity)
                                .setMessage(commonR.string.thread_export_not_connected)
                                .setPositiveButton(commonR.string.ok, null)
                                .show()
                            presenter.finishMatterThreadFlow()
                        }

                        else -> {} // Do nothing
                    }
                }
            }
        }
    }

    /**
     * Registers the appropriate native bridge for the current server.
     *
     * Queries [isServerSupportingExternalAppV2] to determine whether to use the
     * `externalAppV2` bridge or the legacy `externalApp` bridge.
     * V2 also requires [WebViewFeature.WEB_MESSAGE_LISTENER] support;
     * falls back to V1 if the feature is unavailable.
     *
     * Safe to call multiple times: each path removes the previously
     * registered interface before adding the new one.
     */
    @SuppressLint("RequiresFeature")
    private suspend fun webViewAddJavascriptInterface() {
        val isServerSupportingExternalAppV2 =
            serverManager.getServer(presenter.getActiveServer()).isServerSupportingExternalAppV2()
        if (isServerSupportingExternalAppV2 &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        ) {
            webView.removeJavascriptInterface(EXTERNAL_APP_V1)
            registerExternalAppV2()
        } else {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.removeWebMessageListener(webView, EXTERNAL_APP_V2_LISTENER)
            }
            registerExternalAppV1()
        }
    }

    /**
     * Registers the legacy `externalApp` bridge using [android.webkit.WebView.addJavascriptInterface].
     *
     * The HA frontend detects `window.externalApp` and calls named methods directly.
     * Re-registration is safe because the old interface is removed first.
     */
    private fun registerExternalAppV1() {
        webView.removeJavascriptInterface(EXTERNAL_APP_V1)
        webView.addJavascriptInterface(
            object : Any() {
                @JavascriptInterface
                fun getExternalAuth(payload: String) {
                    handleGetExternalAuth(payload = payload.toJsonObjectOrNull())
                }

                @JavascriptInterface
                fun revokeExternalAuth(payload: String) {
                    handleRevokeExternalAuth(payload = payload.toJsonObjectOrNull())
                }

                @JavascriptInterface
                fun externalBus(message: String) {
                    webView.post {
                        val json = message.toJsonObjectOrNull() ?: return@post
                        handleExternalBusMessage(json)
                    }
                }
            },
            EXTERNAL_APP_V1,
        )
    }

    /**
     * Registers the `externalAppV2` bridge using [WebViewCompat.addWebMessageListener].
     *
     * The HA frontend detects `window.externalAppV2.postMessage` and sends all messages
     * through it with a `type` discriminator. Messages are rejected if they come from
     * an iframe or from an origin that doesn't match the currently loaded server URL.
     */
    @SuppressLint("RequiresFeature")
    private fun registerExternalAppV2() {
        WebViewCompat.removeWebMessageListener(webView, EXTERNAL_APP_V2_LISTENER)
        WebViewCompat.addWebMessageListener(
            webView,
            EXTERNAL_APP_V2_LISTENER,
            setOf("*"),
        ) { _, message, sourceOrigin, isMainFrame, _ ->
            if (!isMainFrame) {
                Timber.w("Ignored message from iframe")
                return@addWebMessageListener
            }
            if (!sourceOrigin.hasSameOrigin(loadedUrl)) {
                Timber.w("Ignored message from unexpected origin: ${sensitive(sourceOrigin.toString())}")
                return@addWebMessageListener
            }

            val data = message.data ?: return@addWebMessageListener
            val json = data.toJsonObjectOrNull() ?: return@addWebMessageListener
            val type = json.getStringOrNull("type") ?: return@addWebMessageListener
            val payload = json["payload"]?.jsonObjectOrNull()

            when (type) {
                "getExternalAuth" -> handleGetExternalAuth(payload)
                "revokeExternalAuth" -> handleRevokeExternalAuth(payload)

                "externalBus" -> {
                    if (payload == null) {
                        Timber.w("externalBus message missing payload")
                        return@addWebMessageListener
                    }
                    webView.post {
                        handleExternalBusMessage(payload)
                    }
                }

                else -> Timber.w("Unknown bridge message type: $type")
            }
        }
    }

    /**
     * Validates and handles a `getExternalAuth` request from either V1 or V2 bridge.
     *
     * Rejects requests whose callback name does not match the expected [EXPECTED_GET_AUTH_CALLBACK].
     */
    private fun handleGetExternalAuth(payload: JsonObject?) {
        val callback = payload?.getStringOrNull("callback") ?: ""
        if (FailFast.failWhen(callback != EXPECTED_GET_AUTH_CALLBACK) {
                "Aborting getExternalAuth: callback '$callback' does not match expected '$EXPECTED_GET_AUTH_CALLBACK'"
            }
        ) {
            return
        }
        presenter.onGetExternalAuth(
            this,
            callback,
            force = payload?.getBooleanOrNull("force") ?: false,
        )
    }

    /**
     * Validates and handles a `revokeExternalAuth` request from either V1 or V2 bridge.
     *
     * Rejects requests whose callback name does not match the expected [EXPECTED_REVOKE_AUTH_CALLBACK].
     */
    private fun handleRevokeExternalAuth(payload: JsonObject?) {
        val callback = payload?.getStringOrNull("callback") ?: ""
        if (FailFast.failWhen(callback != EXPECTED_REVOKE_AUTH_CALLBACK) {
                "Aborting revokeExternalAuth: callback '$callback' does not match expected '$EXPECTED_REVOKE_AUTH_CALLBACK'"
            }
        ) {
            return
        }
        presenter.onRevokeExternalAuth(callback)
        isRelaunching = true
    }

    /**
     * Handles an external bus message received from the frontend via the native bridge.
     */
    private fun handleExternalBusMessage(json: JsonObject) {
        val type = json.getStringOrNull("type")
        val messageId = json.getIntOrNull("id")
        Timber.d("External bus id=$messageId type=$type raw=${sensitive { json.toString() }}")
        when (type) {
            "connection-status" -> {
                isConnected = json["payload"]?.jsonObjectOrNull()
                    ?.getStringOrNull("event") == "connected"
                if (isConnected) {
                    alertDialog?.cancel()
                    presenter.checkSecurityVersion()
                }
            }

            "config/get" -> {
                val pm: PackageManager = this@WebViewActivity.packageManager
                val hasNfc = pm.hasSystemFeature(PackageManager.FEATURE_NFC)
                val canCommissionMatter = presenter.appCanCommissionMatterDevice()
                val canExportThread = presenter.appCanExportThreadCredentials()
                val hasBarCodeScanner =
                    if (
                        pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) &&
                        !isAutomotive()
                    ) {
                        1
                    } else {
                        0
                    }
                sendExternalBusMessage(
                    ExternalConfigResponse(
                        id = messageId,
                        hasNfc = hasNfc,
                        canCommissionMatter = canCommissionMatter,
                        canExportThread = canExportThread,
                        hasBarCodeScanner = hasBarCodeScanner,
                        appVersion = appVersionProvider(),
                    ),
                )

                // TODO This feature is deprecated and should be removed after 2022.6
                getAndSetStatusBarNavigationBarColors()

                // TODO This feature is deprecated and should be removed after 2022.6
                // Set event listener for HA theme change
                lifecycleScope.launch {
                    val themeCallback = externalBusCallback("{type:'onHomeAssistantSetTheme'}")
                    webView.evaluateJavascript(
                        "document.addEventListener('settheme', $themeCallback);",
                        null,
                    )
                }
            }

            "assist/show" -> {
                val payload = json["payload"]?.jsonObjectOrNull()
                startActivity(
                    AssistActivity.newInstance(
                        this@WebViewActivity,
                        serverId = presenter.getActiveServer(),
                        pipelineId = payload?.getStringOrNull("pipeline_id"),
                        startListening = payload?.getBooleanOrNull("start_listening") ?: true,
                    ),
                )
            }

            "assist/settings" -> startActivity(
                SettingsActivity.newInstance(
                    this@WebViewActivity,
                    SettingsActivity.Deeplink.AssistSettings,
                ),
            )

            "config_screen/show" ->
                startActivity(
                    SettingsActivity.newInstance(this@WebViewActivity),
                )

            "tag/write" ->
                writeNfcTag.launch(
                    WriteNfcTag.Input(
                        tagId = json["payload"]?.jsonObjectOrNull()?.getStringOrNull("tag"),
                        messageId = json.getIntOrElse("id", -1),
                    ),
                )

            "matter/commission" -> presenter.startCommissioningMatterDevice(this@WebViewActivity)
            "thread/import_credentials" -> {
                presenter.exportThreadCredentials(this@WebViewActivity)

                alertDialog = AlertDialog.Builder(this@WebViewActivity)
                    .setMessage(commonR.string.thread_debug_active)
                    .create()
                alertDialog?.show()
            }

            "bar_code/scan" -> {
                val payload = json["payload"]?.jsonObjectOrNull()
                if (payload?.containsKey("title") != true ||
                    !payload.containsKey("description")
                ) {
                    return
                }
                startActivity(
                    BarcodeScannerActivity.newInstance(
                        this@WebViewActivity,
                        messageId = messageId ?: 0,
                        title = payload.getStringOrElse("title", ""),
                        subtitle = payload.getStringOrElse("description", ""),
                        action = payload.getStringOrNull("alternative_option_label")?.ifBlank {
                            null
                        },
                    ),
                )
            }

            "improv/scan" -> scanForImprov()
            "improv/configure_device" -> {
                val payload = json["payload"]?.jsonObjectOrNull()
                val deviceName = payload?.getStringOrNull("name") ?: return
                configureImprovDevice(deviceName)
            }

            "exoplayer/play_hls" -> exoPlayHls(json)
            "exoplayer/stop" -> exoStopHls()
            "exoplayer/resize" -> exoResizeHls(json)
            "haptic" -> processHaptic(
                json["payload"]?.jsonObjectOrNull()?.getStringOrNull("hapticType") ?: "",
            )

            "theme-update" -> getAndSetStatusBarNavigationBarColors()
            "entity/add_to/get_actions" -> getActions(json)
            "entity/add_to" -> addEntityTo(json)

            "onHomeAssistantSetTheme" -> {
                lifecycleScope.launch(Dispatchers.Main) {
                    getAndSetStatusBarNavigationBarColors()
                }
            }

            "handleBlob" -> {
                val blobData = json.getStringOrNull("data")
                val filename = json.getStringOrNull("filename")
                blobData?.let {
                    lifecycleScope.launch {
                        dataUriDownloadManager.saveDataUri(
                            url = it,
                            mimetype = "",
                            filename = filename,
                        )
                    }
                }
            }

            else -> presenter.onExternalBusMessage(json)
        }
    }

    private fun addEntityTo(json: JsonObject) {
        val payload = json["payload"]?.jsonObjectOrNull()
        val entityId = payload?.getStringOrNull("entity_id")
        val appPayload = payload?.getStringOrNull("app_payload")
        if (entityId != null && appPayload != null) {
            val action = ExternalEntityAddToAction.appPayloadToAction(appPayload)
            lifecycleScope.launch {
                entityAddToHandler.execute(this@WebViewActivity, action, entityId) { message, action ->
                    snackbarHostState.showSnackbar(
                        message,
                        action,
                        duration = SnackbarDuration.Short,
                    ) == SnackbarResult.ActionPerformed
                }
            }
        } else {
            FailFast.fail { "Missing entity_id or app_payload to addEntityTo" }
        }
    }

    private fun getActions(json: JsonObject) {
        val payload = json["payload"]?.jsonObjectOrNull()
        val entityId = payload?.getStringOrNull("entity_id")
        entityId?.let {
            lifecycleScope.launch {
                val actions = entityAddToHandler.actionsForEntity(this@WebViewActivity, entityId)
                sendExternalBusMessage(
                    EntityAddToActionsResponse(
                        id = json["id"],
                        actions = actions.map { action ->
                            ExternalEntityAddToAction.fromAction(this@WebViewActivity, action)
                        },
                    ),
                )
            }
        } ?: FailFast.fail {
            "entity_id not present in response from External bus for `entity/add_to/get_actions`"
        }
    }

    private fun handleWebViewGesture(direction: GestureDirection, pointerCount: Int) {
        lifecycleScope.launch {
            when (presenter.getGestureAction(direction, pointerCount)) {
                GestureAction.NONE -> {
                    // Do nothing
                }

                GestureAction.QUICKBAR_DEFAULT -> {
                    if (serverManager.getServer(presenter.getActiveServer())?.version?.isAtLeast(2026, 2) == true) {
                        webView.dispatchKeyDownEventToDocument("k", "KeyK", keyCode = 75, ctrlKey = true)
                    } else {
                        webView.dispatchKeyDownEventToDocument("e", "KeyE", keyCode = 69)
                    }
                }

                GestureAction.QUICKBAR_ENTITIES -> {
                    webView.dispatchKeyDownEventToDocument("e", "KeyE", keyCode = 69)
                }

                GestureAction.QUICKBAR_DEVICES -> {
                    webView.dispatchKeyDownEventToDocument("d", "KeyD", 68)
                }

                GestureAction.QUICKBAR_COMMANDS -> {
                    webView.dispatchKeyDownEventToDocument("c", "KeyC", 67)
                }

                GestureAction.SHOW_SIDEBAR -> sendExternalBusMessage(ShowSidebar)

                GestureAction.OPEN_ASSIST -> startActivity(
                    AssistActivity.newInstance(
                        this@WebViewActivity,
                        serverId = presenter.getActiveServer(),
                    ),
                )

                GestureAction.NAVIGATE_FORWARD -> {
                    if (webView.canGoForward()) webView.goForward()
                }

                GestureAction.NAVIGATE_DASHBOARD -> navigateToDefaultDashboard()

                GestureAction.NAVIGATE_RELOAD -> {
                    webView.reload()
                }

                GestureAction.SERVER_LIST -> {
                    val serverChooser = ServerChooserFragment()
                    supportFragmentManager.setFragmentResultListener(
                        ServerChooserFragment.RESULT_KEY,
                        this@WebViewActivity,
                    ) {
                            _,
                            bundle,
                        ->
                        if (bundle.containsKey(ServerChooserFragment.RESULT_SERVER)) {
                            lifecycleScope.launch {
                                presenter.switchActiveServer(
                                    lifecycle,
                                    bundle.getInt(ServerChooserFragment.RESULT_SERVER),
                                )
                            }
                        }
                        supportFragmentManager.clearFragmentResultListener(ServerChooserFragment.RESULT_KEY)
                    }
                    serverChooser.show(supportFragmentManager, ServerChooserFragment.TAG)
                }

                GestureAction.SERVER_NEXT -> presenter.nextServer(lifecycle)

                GestureAction.SERVER_PREVIOUS -> presenter.previousServer(lifecycle)

                GestureAction.OPEN_APP_SETTINGS -> startActivity(SettingsActivity.newInstance(this@WebViewActivity))

                GestureAction.OPEN_APP_DEVELOPER -> startActivity(
                    SettingsActivity.newInstance(
                        context = this@WebViewActivity,
                        screen = SettingsActivity.Deeplink.Developer,
                    ),
                )
            }
        }
    }

    private fun getAndSetStatusBarNavigationBarColors() {
        val htmlArraySpacer = "-SPACER-"
        webView.evaluateJavascript(
            "[" +
                "document.getElementsByTagName('html')[0].computedStyleMap().get('--app-header-background-color')[0]," +
                "document.getElementsByTagName('html')[0].computedStyleMap().get('--primary-background-color')[0]" +
                "].join('" + htmlArraySpacer + "')",
        ) { webViewColors ->
            lifecycleScope.launch(Dispatchers.Main) {
                var statusBarColor = 0
                var backgroundColor = 0

                if (!webViewColors.isNullOrEmpty() && webViewColors != "null") {
                    val trimmedColorString = webViewColors.substring(1, webViewColors.length - 1).trim()
                    val colors = trimmedColorString.split(htmlArraySpacer)

                    Timber.d("Color from webview is \"$trimmedColorString\"")

                    statusBarColor = presenter.parseWebViewColor(colors[0].trim())
                    backgroundColor = presenter.parseWebViewColor(colors[1].trim())
                }

                setStatusBarAndBackgroundColor(statusBarColor, backgroundColor)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        presenter.onStart(this)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            // if null it means that the settings were not yet read so we should not recreate
            if (currentAutoplay != null && currentAutoplay != presenter.isAutoPlayVideoEnabled()) {
                recreate()
            }

            appLocked.value = presenter.isAppLocked()
            presenter.updateActiveServer()
        }

        setWebViewZoom()

        lifecycleScope.launch {
            SensorWorker.start(this@WebViewActivity)
            WebsocketManager.start(this@WebViewActivity)

            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG || presenter.isWebViewDebugEnabled())

            requestedOrientation = when (presenter.getScreenOrientation()) {
                getString(
                    R.string.screen_orientation_option_array_value_portrait,
                ),
                -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

                getString(
                    R.string.screen_orientation_option_array_value_landscape,
                ),
                -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }

            if (presenter.isKeepScreenOnEnabled()) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            checkAndWarnForDisabledLocation()
            changeLog.showChangeLog(this@WebViewActivity, false)
        }

        if (loadedUrl != null) {
            waitForConnection()
        }
    }

    override fun onStop() {
        super.onStop()
        lifecycleScope.launch {
            openFirstViewOnDashboardIfNeeded()
        }
    }

    override fun onPause() {
        super.onPause()
        lifecycleScope.launch {
            presenter.setAppActive(false)
        }
        if (!isFinishing && !isRelaunching) SensorReceiver.updateAllSensors(this)
    }

    private suspend fun checkAndWarnForDisabledLocation() {
        var showLocationDisabledWarning = false
        val settingsWithLocationPermissions = mutableListOf<String>()
        if (!DisabledLocationHandler.isLocationEnabled(this) && presenter.isSsidUsed()) {
            showLocationDisabledWarning = true
            settingsWithLocationPermissions.add(getString(commonR.string.pref_connection_homenetwork))
        }
        for (manager in SensorReceiver.MANAGERS) {
            for (basicSensor in manager.getAvailableSensors(this)) {
                if (manager.isEnabled(this, basicSensor)) {
                    val permissions = manager.requiredPermissions(this, basicSensor.id)

                    val fineLocation = DisabledLocationHandler.containsLocationPermission(permissions, true)
                    val coarseLocation = DisabledLocationHandler.containsLocationPermission(permissions, false)

                    if ((fineLocation || coarseLocation)) {
                        if (!DisabledLocationHandler.isLocationEnabled(this)) showLocationDisabledWarning = true
                        settingsWithLocationPermissions.add(getString(basicSensor.name))
                    }
                }
            }
        }

        if (showLocationDisabledWarning) {
            DisabledLocationHandler.showLocationDisabledWarnDialog(
                this@WebViewActivity,
                settingsWithLocationPermissions.toTypedArray(),
                true,
            )
        } else {
            DisabledLocationHandler.removeLocationDisabledWarning(this@WebViewActivity)
        }
    }

    fun exoPlayHls(json: JsonObject) {
        val payload = json["payload"]?.jsonObjectOrNull()
        val uri = payload?.getStringOrNull("url")?.toUri() ?: return
        val isMuted = payload.getBooleanOrElse("muted", false)
        lifecycleScope.launch {
            exoPlayer.value = initializePlayer(this@WebViewActivity, dataSourceFactory).apply {
                setMediaItem(MediaItem.fromUri(uri))
                playWhenReady = true
                addListener(
                    object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            super.onVideoSizeChanged(videoSize)
                            if (videoSize.height == 0 || videoSize.width == 0) return
                            playerSize.value?.let {
                                // If height is already set, it means the frontend has imposed a constraint; avoid overriding.
                                if (it.height == 0.dp) {
                                    playerSize.value = DpSize(it.width, it.width * videoSize.height / videoSize.width)
                                }
                            }
                        }
                    },
                )
                prepare()
                volume = if (isMuted) 0f else 1f
            }
        }
        sendExternalBusMessage(
            ExternalBusMessage(
                id = json["id"],
                type = "result",
                success = true,
                callback = {
                    Timber.d("Callback $it")
                },
            ),
        )
    }

    fun exoStopHls() {
        runOnUiThread {
            // We might be in fullscreen mode, so we display back the system UI just in case
            // same for the fullscreen status of ExoPlayer
            isExoFullScreen = false
            showSystemUI()
            exoPlayer.value?.release()
            exoPlayer.value = null
            playerSize.value = null
            playerTop.value = 0.dp
            playerLeft.value = 0.dp
        }
    }

    @OptIn(UnstableApi::class)
    fun exoResizeHls(json: JsonObject) {
        val payload = json["payload"]?.jsonObjectOrNull() ?: return
        // Payload is https://developer.mozilla.org/en-US/docs/Web/API/Element/getBoundingClientRect
        // The values are already scaled to the screen.
        // We only need to store the top left corner for the offset and the player size

        val left = payload.getIntOrElse("left", 0)
        val top = payload.getIntOrElse("top", 0)
        val right = payload.getIntOrElse("right", 0)
        // if the bottom value is not 0 we should take it it is a constraint from the frontend, otherwise we try to compute the
        // height based on the video's aspect ratio if available.
        val bottom =
            payload.getIntOrNull("bottom")?.takeIf { it > 0 } ?: exoPlayer.value?.videoFormat?.let { videoFormat ->
                if (videoFormat.width > 0) {
                    // Calculate height of the video based on aspect ratio
                    val width = right - left
                    val videoHeight = width * videoFormat.height / videoFormat.width
                    (top + videoHeight)
                } else {
                    payload.getIntOrNull("bottom")
                }
            } ?: payload.getIntOrElse("bottom", 0)

        playerTop.value = top.dp
        playerLeft.value = left.dp
        playerSize.value = DpSize((right - left).dp, (bottom - top).dp)
    }

    fun processHaptic(hapticType: String) {
        Timber.d("Processing haptic tag for $hapticType")
        HapticFeedbackPerformer.perform(
            webView,
            when (hapticType) {
                "success" -> HapticType.Success
                "warning" -> HapticType.Warning
                "failure" -> HapticType.Failure
                "light" -> HapticType.Light
                "medium" -> HapticType.Medium
                "heavy" -> HapticType.Heavy
                "selection" -> HapticType.Selection
                else -> HapticType.Unknown
            },
        )
    }

    private fun authenticationResult(result: Int) {
        when (result) {
            Authenticator.SUCCESS -> {
                Timber.d("Authentication successful, unlocking app")
                appLocked.value = false
                lifecycleScope.launch {
                    presenter.setAppActive(true)
                }
            }

            Authenticator.CANCELED -> {
                Timber.d("Authentication canceled by user, closing activity")
                finishAffinity()
            }

            else -> Timber.d("Authentication failed, retry attempts allowed")
        }
        unlockingApp = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isFinishing) {
            lifecycleScope.launch {
                unlockAppIfNeeded()

                if (presenter.isFullScreen() || isVideoFullScreen) {
                    hideSystemUI()
                } else {
                    showSystemUI()
                }

                var path = intent.getStringExtra(EXTRA_PATH)
                if (path?.startsWith("entityId:") == true) {
                    // Get the entity ID from a string formatted "entityId:domain.entity"
                    // https://github.com/home-assistant/core/blob/dev/homeassistant/core.py#L159
                    val pattern = "(?<=^entityId:)((?!.+__)(?!_)[\\da-z_]+(?<!_)\\.(?!_)[\\da-z_]+(?<!_)$)".toRegex()
                    val entity = pattern.find(path)?.value ?: ""
                    if (
                        entity.isNotBlank() &&
                        serverManager.getServer(presenter.getActiveServer())?.version?.isAtLeast(2025, 6, 0) == true
                    ) {
                        path = "/?more-info-entity-id=$entity"
                    } else {
                        moreInfoEntity = entity
                    }
                }
                intent.removeExtra(EXTRA_PATH)
                presenter.load(lifecycle, path, isInternalOverride)
            }
        }
    }

    override suspend fun unlockAppIfNeeded() {
        appLocked.value = presenter.isAppLocked()
        if (appLocked.value) {
            if (!unlockingApp) {
                authenticator.authenticate(getString(commonR.string.biometric_title))
            }
            unlockingApp = true
        }
    }

    private fun hideSystemUI() {
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun showSystemUI() {
        windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        lifecycleScope.launch {
            presenter.setAppActive(false)
        }
        videoHeight = decor.height
        val bounds = Rect(0, 0, 1920, 1080)
        if (isVideoFullScreen or isExoFullScreen) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val mPictureInPictureParamsBuilder = PictureInPictureParams.Builder()
                mPictureInPictureParamsBuilder.setAspectRatio(
                    Rational(
                        bounds.width(),
                        bounds.height(),
                    ),
                )
                mPictureInPictureParamsBuilder.setSourceRectHint(bounds)
                enterPictureInPictureMode(mPictureInPictureParamsBuilder.build())
            }
        }
    }

    override fun relaunchApp() {
        isRelaunching = true
        startActivity(Intent(this, LaunchActivity::class.java))
        finish()
    }

    override fun loadUrl(url: Uri, keepHistory: Boolean, openInApp: Boolean, serverHandleInsets: Boolean) {
        Timber.d(
            "Loading ${
                sensitive(
                    url.toString(),
                )
            } (keepHistory $keepHistory, openInApp $openInApp, serverHandleInsets $serverHandleInsets)",
        )
        this.serverHandleInsets.value = serverHandleInsets
        if (openInApp) {
            runFragmentTransactionIfStateSafe {
                // Remove any displayed fragments (e.g., BlockInsecureFragment, ConnectionSecurityLevelFragment)
                supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            }
            supportFragmentManager.clearFragmentResultListener(BlockInsecureFragment.RESULT_KEY)

            val oldUrl = loadedUrl
            // It means that if we loaded an URL with a path previously and we try to load the same URL without
            // a path we don't do anything.
            val shouldLoadUrl = !url.hasSameOrigin(oldUrl) || url.hasNonRootPath()
            if (shouldLoadUrl) {
                clearHistory = !keepHistory
                loadedUrl = url
                // Anchor the WebView client to the server host so auth-provider
                // navigation on third-party domains can be distinguished from
                // user-tapped external links on the HA frontend.
                webViewTlsClient?.serverHost = url.host

                loadUrlJob?.cancel()
                loadUrlJob = lifecycleScope.launch {
                    // Register the native bridge depending on the server and webview capabilities
                    webViewAddJavascriptInterface()

                    webView.loadUrl(url.toString())
                    waitForConnection()
                }
            } else {
                Timber.d("Same base URL without meaningful path, skipping load")
            }
        } else {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, url)
                startActivity(browserIntent)
            } catch (e: Exception) {
                Timber.e(e, "Unable to view url")
            }
        }
    }

    override fun showConnectionSecurityLevel(serverId: Int) {
        // Skip if already showing ConnectionSecurityLevelFragment to avoid blinking
        if (supportFragmentManager.fragments.any { it is ConnectionSecurityLevelFragment }) {
            Timber.d("ConnectionSecurityLevelFragment already showing, skipping")
            return
        }

        runFragmentTransactionIfStateSafe {
            supportFragmentManager.setFragmentResultListener(
                ConnectionSecurityLevelFragment.RESULT_KEY,
                this,
            ) { _, _ ->
                Timber.d("Security level screen exited by user, proceeding with URL loading")
                supportFragmentManager.clearFragmentResultListener(ConnectionSecurityLevelFragment.RESULT_KEY)

                // Mark as shown so we don't show the fragment again for this server
                presenter.onConnectionSecurityLevelShown()

                lifecycleScope.launch {
                    // Trigger a reload of the URL via the presenter to trigger loadUrl in the view.
                    // The presenter will apply the potential changes made in the fragment.
                    presenter.load(lifecycle, isInternalOverride = isInternalOverride)
                }
            }

            supportFragmentManager.beginTransaction()
                .replace(
                    android.R.id.content,
                    ConnectionSecurityLevelFragment.newInstance(
                        serverId = serverId,
                        handleAllInsets = true,
                        useCloseButton = true,
                    ),
                )
                .addToBackStack(null)
                .commit()
        }
    }

    override fun showBlockInsecure(serverId: Int) {
        // Skip if already showing BlockInsecureFragment to avoid blinking on retry
        if (supportFragmentManager.fragments.any { it is BlockInsecureFragment }) {
            Timber.d("BlockInsecureFragment already showing, skipping")
            return
        }
    }
}
