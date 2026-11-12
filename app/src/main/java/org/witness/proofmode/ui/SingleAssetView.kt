package org.witness.proofmode.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.location.Location
import android.net.Uri
import android.os.Build
import android.preference.PreferenceManager
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.contentauth.c2pa.C2PA
import org.json.JSONObject
import org.witness.proofmode.ProofMode
import org.witness.proofmode.R
import org.witness.proofmode.ui.media.ProofItemMedia
import org.witness.proofmode.ui.media.isVideoItem
import org.witness.proofmode.c2pa.C2PAManager
import org.witness.proofmode.c2pa.PreferencesManager
import org.witness.proofmode.c2pa.ValidationState
import org.witness.proofmode.notaries.NostrNotarizationVerifier
import org.witness.proofmode.plugins.ipfscid.IpfsCidSidecar
import org.witness.proofmode.plugins.lp.attestation.LocationProtocolArtifactStore
import org.witness.proofmode.plugins.lp.autocapture.AutoCaptureLpStateRegistry
import org.witness.proofmode.service.MediaWatcher
import org.witness.proofmode.service.ProofModeV1Constants
import org.witness.proofmode.share.FilebaseSocialShareHelper
import org.witness.proofmode.storage.DefaultStorageProvider
import org.witness.proofmode.storage.StorageProvider
import org.witness.proofmode.storage.proofset.ProofSetUploader
import org.witness.proofmode.ui.ProofOverviewArtifactSummaries
import org.witness.proofmode.storage.filebase.FilebaseSidecarContract
import org.witness.proofmode.util.ProofModeUtil
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.lang.Float.max
import java.lang.Float.min
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.Date
import java.util.Locale


val LocalShowMetadata = compositionLocalOf<Boolean> { true }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleAssetViewWithToolbar(initialItem: ProofableItem, onClose: () -> Unit) {

    // Open on the media itself; the info button or a drag up brings in the metadata.
    var showMetadata by remember {
        mutableStateOf(false)
    }

    var title by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = title, color = Color.White)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onSecondary
                ),
                navigationIcon = {
                    IconButton(onClick = {
                        onClose()
                    }) {
                        Icon(Icons.Filled.ArrowBack, "")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        showMetadata = !showMetadata
                    }) {
                        Icon(if (showMetadata) Icons.Filled.Info else Icons.Outlined.Info, "")
                    }
                }
            )
        }, content = { padding ->
            CompositionLocalProvider(LocalShowMetadata provides showMetadata) {
            SingleAssetView(
                initialItem = initialItem,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                setShowMetadata = { show -> showMetadata = show},
                setTitle = { newTitle -> title = newTitle },

            )
            }

        })
}

@Composable
fun SingleAssetView(initialItem: ProofableItem, modifier: Modifier = Modifier, setShowMetadata: (Boolean) -> Unit, setTitle: (String) -> Unit) {
    val context = LocalContext.current
    var dragOffset by remember {
        mutableStateOf(0f)
    }
    var topPartHeight by remember {
        mutableStateOf(0f)
    }
    var topPartWidth by remember {
        mutableStateOf(0f)
    }

    // Everything in the activity log, newest first like the feed, so a sideways swipe
    // walks the same sequence the user was just looking at. Taken once when the viewer
    // opens: a capture landing mid-swipe must not shift the pages under the finger.
    val allAssets = remember(initialItem.uri) {
        val all = Activities.activities.asReversed()
            .flatMap { Activities.getActivityProofableItems(it) }
            .distinctBy { it.uri.toString() }
            .filter { !it.isDeleted(context) }
        if (all.any { it.uri == initialItem.uri }) all else listOf(initialItem) + all
    }
    val pagerState = rememberPagerState(
        initialPage = allAssets.indexOfFirst { it.uri == initialItem.uri }.coerceAtLeast(0)
    ) { allAssets.size }
    val currentItem = allAssets[pagerState.currentPage.coerceIn(0, allAssets.size - 1)]

    val titleFormat = stringResource(id = R.string.date_display_single_item)
    LaunchedEffect(currentItem.id) {
        Activities.dateForItem(item = currentItem, context = context) { date ->
            setTitle(SimpleDateFormat(titleFormat).format(date))
        }
    }

    val showMetadata = LocalShowMetadata.current
    val metadataOpacity: Float by animateFloatAsState(
        targetValue =
        if (dragOffset != 0f)
            max(min(1f, (if (showMetadata) 1f else 0f) - (dragOffset / (topPartHeight / 3))), 0f)
        else if (showMetadata)
            1f
        else
            0f, animationSpec = spring(
            dampingRatio = Spring.DampingRatioHighBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )
    val localDensity = LocalDensity.current
    val itemViewHeight = with(localDensity) {
        val dpHalfHeight = (0.5f * topPartHeight).toDp()
        // Half the space beside the metadata panel, all of it once the panel is hidden.
        dpHalfHeight.plus(dpHalfHeight.times(1 - metadataOpacity))
    }
    var metadataRevision by remember(currentItem.id) { mutableStateOf(0) }
    LaunchedEffect(currentItem.id) {
        ProofSetUploader.occupancyUpdates.collect { hash ->
            metadataRevision = nextMetadataRevision(currentItem.id, hash, metadataRevision)
        }
    }
    LaunchedEffect(currentItem.id) {
        AutoCaptureLpStateRegistry.updates.collect { hash ->
            metadataRevision = nextMetadataRevision(currentItem.id, hash, metadataRevision)
        }
    }
    // currentItem is a copy taken when the view was opened; follow the live feed
    // item so a pending capture updates here once it has been signed.
    val item = Activities.itemForUri(currentItem.uri) ?: currentItem
    // PROOF_GENERATED is broadcast just before the proof sidecar is written, so
    // re-read the metadata for a few seconds after an item completes while open.
    LaunchedEffect(item.proofStatus) {
        if (item.proofStatus == ProofStatus.GENERATED && currentItem.proofStatus != ProofStatus.GENERATED) {
            repeat(5) {
                delay(1000)
                metadataRevision++
            }
        }
    }
    val metadataRefresh = metadataRevision

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier
            .weight(1f, true)
            .background(Color.Black)
            .onGloballyPositioned { coordinates ->
                topPartHeight = coordinates.size.height.toFloat()
                topPartWidth = coordinates.size.width.toFloat()
            }
        ) {
            BoxWithConstraints(modifier = Modifier
                .fillMaxWidth()
                .height(itemViewHeight)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        dragOffset += delta
                    },
                    onDragStopped = { _ ->
                        if (metadataOpacity > 0.2 && !showMetadata) {
                            setShowMetadata(true)
                        } else if (metadataOpacity < 0.8 && showMetadata) {
                            setShowMetadata(false)
                        }
                        dragOffset = 0f
                    }
                )
            ) {
                val pageWidth = maxWidth
                val itemSizeMultiplier by animateFloatAsState(
                    targetValue = if (LocalSelectionHandler.current.anySelected()) 0.7f else 1f
                )
                // The pager only sees one-finger sideways drags the image didn't claim:
                // ZoomableBox consumes pinches and pans while zoomed in, so a zoomed
                // image pans instead of paging, and at 1x a swipe changes item.
                HorizontalPager(
                    state = pagerState,
                    pageSpacing = 10.dp,
                    key = { page -> allAssets[page].uri.toString() },
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = itemSizeMultiplier
                                scaleY = itemSizeMultiplier
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        SingleItemView(
                            itemWidth = pageWidth,
                            allAssets = allAssets,
                            index = page,
                            selectedIndex = pagerState.currentPage
                        )
                    }
                }
            }
            // Only lay the metadata panel out while it is actually visible. When it is
            // faded out the image expands over the space it occupies, and an invisible
            // panel left in the layout keeps hit-testing - it would swallow the pinch,
            // pan and double-tap gestures over the bottom half of the expanded image.
            if (metadataOpacity > 0f) {
                Column(
                    modifier = Modifier
                        .alpha(metadataOpacity)
                        .height(with(localDensity) { (topPartHeight / 2).toDp() })
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(6.dp, 6.dp)
                        .background(Color.White)
                        .verticalScroll(rememberScrollState())
                ) {

                    if (item.proofStatus != ProofStatus.GENERATED) {
                        PendingProofRow(item)
                    }

                    // Keyed on the item too, so the rows' expanded/loaded state
                    // doesn't carry over from one page to the next.
                    key(currentItem.uri, metadataRefresh) {
                        updateMetadata(currentItem.uri, context)
                    }

                }
            }

        }
        val handler = LocalSelectionHandler.current
        val items = actionBarItems(
            anySelected = handler.anySelected(),
            selectedItems = handler.selectedItems(),
            currentItem = currentItem,
        )
        ProofsetActionBar(
            items = items,
            showCancel = false,
            onCancel = {},
            onShare = { shareItems ->
                (context as? ActivitiesViewDelegate)?.shareItems(shareItems, fileName = null, shareText = null)
            },
        )
    }
}

// Shown in place of the (still empty) metadata for a capture that has not been
// signed yet, e.g. because the signing server could not be reached. Retry runs it
// back through MediaWatcher; the feed item then advances via the usual proof events.
@Composable
fun PendingProofRow(item: ProofableItem) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var retrying by remember { mutableStateOf(false) }
    var retryStatus by remember { mutableStateOf<String?>(null) }
    val generating = item.proofStatus == ProofStatus.GENERATING

    Row {
        Text(
            modifier = Modifier.padding(3.dp, 3.dp),
            text = stringResource(
                if (generating) R.string.proof_generating_message else R.string.proof_pending_message
            ),
            fontWeight = FontWeight.Bold
        )
    }
    if (!generating) {
        Row {
            Button(
                modifier = Modifier.padding(3.dp, 3.dp),
                enabled = !retrying,
                onClick = {
                    retrying = true
                    retryStatus = context.getString(R.string.retry_in_progress)
                    coroutineScope.launch {
                        val result = runCatching {
                            MediaWatcher.getInstance(context.applicationContext)?.retryIngest(item.uri)
                        }.getOrNull()
                        retryStatus = context.getString(
                            when (result) {
                                MediaWatcher.RetryResult.STARTED -> R.string.retry_started
                                MediaWatcher.RetryResult.OFFLINE -> R.string.retry_offline
                                null -> R.string.retry_failed
                            }
                        )
                        retrying = false
                    }
                }
            ) {
                Text(text = stringResource(R.string.retry_action))
            }
        }
        retryStatus?.let { status ->
            Row {
                Text(
                    modifier = Modifier.padding(3.dp, 3.dp),
                    text = status,
                    color = Color.Gray
                )
            }
        }
    }
}

fun nextMetadataRevision(itemId: String, eventHash: String, current: Int): Int =
    if (itemId.isNotBlank() && eventHash == itemId) current + 1 else current

@Composable
fun updateMetadata (itemUri : Uri, context : Context) {
    var hash = try {
        ProofModeUtil.getProofHash(itemUri,context)
    } catch (ex: FileNotFoundException) {
        Timber.e("FIle with uri ${itemUri.path} not found", ex)
        null
    } catch (ex: Exception) {
        Timber.e("Error getting hash for uri ${itemUri.path}", ex)
        null
    }
    if (hash != null) {

        val dfParse: DateFormat = SimpleDateFormat(
            ProofModeV1Constants.ISO_DATE_TIME_FORMAT,
            Locale.US
        ) // Quoted "Z" is a literal, so the UTC zone has to be set explicitly
        dfParse.timeZone = TimeZone.getTimeZone("UTC")


        var df = SimpleDateFormat.getDateTimeInstance()

        //ProofMode app uses default storage provider
        var storageProvider = DefaultStorageProvider(context)
        var hmap: HashMap<String, String>? =
            ProofModeUtil.getProofHashMap(storageProvider, hash)

        if (hmap?.contains(ProofModeV1Constants.FILE_PATH) == true) {
            var c2paFile = File(hmap?.get(ProofModeV1Constants.FILE_PATH))

            var validationState = ValidationState.INVALID

            try {
                val c2paMan = C2PAManager(context, PreferencesManager(context))
                validationState = c2paMan.validateSignedMedia(c2paFile.canonicalPath)
            } catch (e: Exception) {
                ValidationState.INVALID
            }

            C2PAManifestRow(
                label = context.getString(R.string.content_credentials),
                validationState = validationState,
                filePath = c2paFile.canonicalPath,
                mediaHash = hash,
                context = context
            )

        }

        // Nostr notarization verification, shown just below the C2PA / Content Credentials check.
        val nostrIdentifier = hash + ProofMode.NOSTR_FILE_TAG
        if (storageProvider.proofIdentifierExists(hash, nostrIdentifier)) {
            val nostrJson = try {
                storageProvider.getInputStream(hash, nostrIdentifier)
                    ?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) {
                Timber.e(e, "Error reading .nostr notarization file")
                null
            }
            if (!nostrJson.isNullOrEmpty()) {
                NostrVerificationRow(
                    label = context.getString(R.string.nostr_notarization),
                    nostrJson = nostrJson
                )
            }
        }

        val tapToView = context.getString(R.string.artifact_tap_to_view)
        val tapManifest = context.getString(R.string.artifact_tap_to_view_manifest)
        val lpPending = context.getString(R.string.lp_attestation_in_progress)
        val lpState = AutoCaptureLpStateRegistry.getState(hash)

        val cidIdentifier = IpfsCidSidecar.sidecarBasename(hash)
        if (storageProvider.proofIdentifierExists(hash, cidIdentifier)) {
            CidManifestRow(
                label = context.getString(R.string.cid_manifest),
                tapHint = tapManifest,
                mediaHash = hash,
                identifier = cidIdentifier,
                storageProvider = storageProvider,
            )
        }

        val offchainId = hash + LocationProtocolArtifactStore.OFFCHAIN_SUFFIX
        val hasOffchain = storageProvider.proofIdentifierExists(hash, offchainId)
        when (lpMetadataPhase(lpState.offchain, hasOffchain)) {
            MetadataPhase.READY -> OffchainLpAttestationRow(
                label = context.getString(R.string.offchain_location_attestation),
                tapHint = tapToView,
                mediaHash = hash,
                identifier = offchainId,
                storageProvider = storageProvider,
            )
            MetadataPhase.PENDING -> addRow(
                context.getString(R.string.offchain_location_attestation),
                lpPending,
            )
            MetadataPhase.HIDDEN -> Unit
        }

        val onchainId = hash + LocationProtocolArtifactStore.ONCHAIN_SUFFIX
        val hasOnchain = storageProvider.proofIdentifierExists(hash, onchainId)
        when (lpMetadataPhase(lpState.onchain, hasOnchain)) {
            MetadataPhase.READY -> OnchainLpAttestationRow(
                label = context.getString(R.string.onchain_location_attestation),
                tapHint = tapToView,
                mediaHash = hash,
                identifier = onchainId,
                storageProvider = storageProvider,
            )
            MetadataPhase.PENDING -> addRow(
                context.getString(R.string.onchain_location_attestation),
                lpPending,
            )
            MetadataPhase.HIDDEN -> Unit
        }

        val proofsetId = hash + FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX
        val imageId = hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX
        val proofsetUrl = if (storageProvider.proofIdentifierExists(hash, proofsetId)) {
            storageProvider.getInputStream(hash, proofsetId)?.bufferedReader()?.use { it.readText() }
        } else null
        val rawImageUrl = if (storageProvider.proofIdentifierExists(hash, imageId)) {
            storageProvider.getInputStream(hash, imageId)?.bufferedReader()?.use { it.readText() }
        } else null
        val imageUrl = FilebaseSocialShareHelper.overviewFilebaseImageUrl(proofsetUrl, rawImageUrl)
        val filebaseLinks = !proofsetUrl.isNullOrBlank() || !imageUrl.isNullOrBlank()
        FilebaseUploadsGroup(
            proofsetUrl = proofsetUrl?.takeIf { it.isNotBlank() },
            imageUrl = imageUrl?.takeIf { it.isNotBlank() },
            pending = filebaseMetadataPhase(
                occupancy = ProofSetUploader.occupancyCount(hash),
                hasLinks = filebaseLinks,
            ) == MetadataPhase.PENDING,
        )

        if (hmap?.contains(ProofModeV1Constants.PROOF_GENERATED) == true)
            addRow(
                ProofModeV1Constants.PROOF_GENERATED,
                df.format(dfParse.parse(hmap?.get(ProofModeV1Constants.PROOF_GENERATED)))
            )


        addRow(ProofModeV1Constants.FILE_HASH_SHA_256, hmap)
        if (hmap?.contains(ProofModeV1Constants.FILE_CREATED) == true)
            addRow(
                ProofModeV1Constants.FILE_CREATED,
                df.format(dfParse.parse(hmap?.get(ProofModeV1Constants.FILE_CREATED)))
            )
        if (hmap?.contains(ProofModeV1Constants.FILE_MODIFIED) == true)
            addRow(
                ProofModeV1Constants.FILE_MODIFIED,
                df.format(dfParse.parse(hmap?.get(ProofModeV1Constants.FILE_MODIFIED)))
            )


        if (hmap?.contains(ProofModeV1Constants.LOCATION_LATITUDE) == true) {
            var lat = hmap?.get(ProofModeV1Constants.LOCATION_LATITUDE)?.toDouble()
            var loc = hmap?.get(ProofModeV1Constants.LOCATION_LONGITUDE)?.toDouble()

            lat?.let {
                if (loc != null) {
                    convertDegMinsSecs(lat, loc)?.let { it1 -> LocationRow("Location", it1, lat, loc) }
                }
            }
        }

        if (hmap?.contains(ProofModeV1Constants.HARDWARE) == true)
            addRow (ProofModeV1Constants.HARDWARE, hmap)

        if (hmap?.contains(ProofModeV1Constants.FILE_PATH) == true)
            addRow (ProofModeV1Constants.FILE_PATH, hmap)


    }
}
/** OpenStreetMap link that drops a marker on the point and zooms to street level. */
fun openStreetMapUrl(latitude: Double, longitude: Double): String =
    // Double.toString is locale-independent, so no decimal commas end up in the URL.
    "https://www.openstreetmap.org/?mlat=$latitude&mlon=$longitude#map=17/$latitude/$longitude"

/** The location row: the coordinates as text, tappable to open them on OpenStreetMap. */
@Composable
private fun LocationRow(label: String, display: String, latitude: Double, longitude: Double) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(openStreetMapUrl(latitude, longitude)))
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Unable to open OpenStreetMap")
                }
            }
    ) {
        Row {
            Text(modifier = Modifier.padding(3.dp, 3.dp), text = label, fontWeight = FontWeight.Bold)
        }
        Row {
            Text(
                modifier = Modifier.padding(3.dp, 3.dp),
                text = display,
                color = Color(0xFF2E7D32),
            )
        }
        Row {
            Text(modifier = Modifier.padding(3.dp, 3.dp), text = "")
        }
    }
}

@Composable
fun FilebaseUploadsGroup(proofsetUrl: String?, imageUrl: String?, pending: Boolean = false) {
    if (proofsetUrl.isNullOrBlank() && imageUrl.isNullOrBlank() && !pending) return

    Row {
        Text(
            modifier = Modifier.padding(3.dp, 3.dp),
            text = stringResource(R.string.filebase_uploads_group),
            fontWeight = FontWeight.Bold,
        )
    }
    if (pending && proofsetUrl.isNullOrBlank() && imageUrl.isNullOrBlank()) {
        Row {
            Text(
                modifier = Modifier.padding(3.dp, 3.dp),
                text = stringResource(R.string.filebase_uploading),
            )
        }
    } else {
        proofsetUrl?.takeIf { it.isNotBlank() }?.let { url ->
            FilebaseUploadLink(
                label = stringResource(R.string.filebase_view_proofset),
                url = url,
            )
        }
        imageUrl?.takeIf { it.isNotBlank() }?.let { url ->
            FilebaseUploadLink(
                label = stringResource(R.string.filebase_view_uploaded_media),
                url = url,
            )
        }
    }
    // Match Nostr / addRow section spacing before the next metadata group.
    Row {
        Text(modifier = Modifier.padding(2.dp, 2.dp), text = "")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilebaseUploadLink(label: String, url: String) {
    val context = LocalContext.current
    Row {
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (e: Exception) {
                            Timber.e(e, "Unable to open Filebase URL")
                        }
                    },
                    onLongClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(label, url))
                        Toast.makeText(
                            context,
                            R.string.nostr_identity_copied,
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
                .padding(3.dp, 3.dp),
            text = label,
            color = Color(0xFF2E7D32),
        )
    }
}

@Composable
fun C2PAManifestRow(label: String, validationState: ValidationState, filePath: String, mediaHash: String, context: Context) {
    var expanded by remember { mutableStateOf(false) }
    var manifestText by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val onTap: () -> Unit = {
        if (!expanded && manifestText == null && !loading) {
            loading = true
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val c2paMan = C2PAManager(context, PreferencesManager(context))
                        c2paMan.readManifest(filePath)
                    } catch (e: Exception) {
                        Timber.e(e, "Error reading C2PA manifest")
                        "Error reading manifest: ${e.message}"
                    }
                }
                manifestText = result
                loading = false
                expanded = true
            }
        } else {
            expanded = !expanded
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
    ) {
        Row {
            Text(
                modifier = Modifier.padding(3.dp, 3.dp),
                text = label,
                fontWeight = FontWeight.Bold
            )
        }
        Row {
            Text(
                modifier = Modifier.padding(3.dp, 3.dp),
                text = when (validationState) {
                    ValidationState.TRUSTED -> "Trusted: Tap to view manifest"
                    ValidationState.VALID -> "Valid: Tap to view manifest"
                    ValidationState.INVALID -> "Invalid: Tap to view manifest"
                },
                color = when (validationState) {
                    ValidationState.TRUSTED -> Color(0xFF1B5E20)
                    ValidationState.VALID -> Color(0xFF2E7D32)
                    ValidationState.INVALID -> Color(0xFFC62828)
                }
            )
        }
    }

    // An invalid manifest can be re-signed: if local ProofMode data already exists for
    // this file's hash, run it back through MediaWatcher (as a notarization import),
    // producing a freshly C2PA-signed asset and a new item in the Activities feed.
    if (validationState == ValidationState.INVALID) {
        var resigning by remember { mutableStateOf(false) }
        var resignStatus by remember { mutableStateOf<String?>(null) }
        Row {
            Button(
                modifier = Modifier.padding(3.dp, 3.dp),
                enabled = !resigning,
                onClick = {
                    resigning = true
                    resignStatus = context.getString(R.string.resign_in_progress)
                    coroutineScope.launch {
                        val started = withContext(Dispatchers.IO) {
                            runCatching {
                                val mw = MediaWatcher.getInstance(context)
                                if (mw != null && mw.proofExists(mediaHash)) {
                                    mw.resignMedia(
                                        Uri.fromFile(File(filePath)),
                                        mimeTypeForPath(filePath)
                                    )
                                    true
                                } else false
                            }.getOrDefault(false)
                        }
                        resignStatus = context.getString(
                            if (started) R.string.resign_started else R.string.resign_no_proof
                        )
                        resigning = false
                    }
                }
            ) {
                Text(text = stringResource(R.string.resign_action))
            }
        }
        resignStatus?.let { status ->
            Row {
                Text(
                    modifier = Modifier.padding(3.dp, 3.dp),
                    text = status,
                    color = Color.Gray
                )
            }
        }
    }
    if (loading) {
        Row {
            Text(
                modifier = Modifier.padding(3.dp, 3.dp),
                text = "Loading manifest...",
                color = Color.Gray
            )
        }
    }
    if (expanded && manifestText != null) {
        Row {
            SelectionContainer {
                Text(
                    modifier = Modifier
                        .padding(3.dp, 3.dp)
                        .fillMaxWidth()
                        .background(Color(0xFFF5F5F5))
                        .padding(8.dp),
                    text = manifestText!!,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
    Row {
        Text(modifier = Modifier.padding(2.dp, 2.dp), text = "")
    }
}

@Composable
fun NostrVerificationRow(label: String, nostrJson: String) {
    var expanded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<NostrNotarizationVerifier.Result?>(null) }
    var onRelays by remember { mutableStateOf<Boolean?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // Parse header info (event id / note / published flag / relays) without any crypto, for the
    // always-visible summary line. The heavy work (signature + relay checks) runs on tap.
    val header = remember(nostrJson) {
        try {
            val root = JSONObject(nostrJson)
            val eventObj = if (root.has("event")) root.getJSONObject("event") else root
            val eventId = root.optString("eventId").ifEmpty { eventObj.optString("id") }
            val note = root.optString("note")
            val published = if (root.has("published")) root.optBoolean("published") else null
            val relays = root.optJSONArray("relays")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).ifEmpty { null } }
            } ?: DEFAULT_VERIFY_RELAYS
            NostrHeader(eventId, note, published, relays)
        } catch (e: Exception) {
            NostrHeader("", "", null, DEFAULT_VERIFY_RELAYS)
        }
    }

    val onTap: () -> Unit = {
        if (!expanded && result == null && !loading) {
            loading = true
            coroutineScope.launch {
                val verifyResult = withContext(Dispatchers.IO) {
                    NostrNotarizationVerifier.verify(nostrJson)
                }
                result = verifyResult
                val exists = withContext(Dispatchers.IO) {
                    try {
                        NostrNotarizationVerifier.existsOnRelays(nostrJson, header.relays, 15000L)
                    } catch (e: Exception) {
                        Timber.e(e, "Error checking Nostr relays")
                        false
                    }
                }
                onRelays = exists
                loading = false
                expanded = true
            }
        } else {
            expanded = !expanded
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
    ) {
        Row {
            Text(
                modifier = Modifier.padding(3.dp, 3.dp),
                text = label,
                fontWeight = FontWeight.Bold
            )
        }
        Row {
            val idShort = when {
                header.note.isNotEmpty() -> header.note.take(20) + "…"
                header.eventId.isNotEmpty() -> header.eventId.take(16) + "…"
                else -> ""
            }
            Text(
                modifier = Modifier.padding(3.dp, 3.dp),
                text = if (idShort.isNotEmpty()) "$idShort — Tap to verify" else "Tap to verify",
                color = Color.Gray
            )
        }
        header.published?.let { pub ->
            Row {
                Text(
                    modifier = Modifier.padding(3.dp, 3.dp),
                    text = if (pub) "Published to Nostr relays" else "Signed but not yet published",
                    color = if (pub) Color(0xFF2E7D32) else Color(0xFFEF6C00)
                )
            }
        }
    }

    if (loading) {
        Row {
            Text(
                modifier = Modifier.padding(3.dp, 3.dp),
                text = "Verifying signature and checking relays…",
                color = Color.Gray
            )
        }
    }

    if (expanded && result != null) {
        val r = result!!
        Row {
            Text(
                modifier = Modifier.padding(3.dp, 3.dp),
                text = if (r.valid) "Signature: Valid"
                else "Signature: Invalid" + (r.error?.let { " ($it)" } ?: ""),
                color = if (r.valid) Color(0xFF2E7D32) else Color(0xFFC62828)
            )
        }
        onRelays?.let { exists ->
            Row {
                Text(
                    modifier = Modifier.padding(3.dp, 3.dp),
                    text = if (exists) "Relays: Found on Nostr network"
                    else "Relays: Not found / unreachable",
                    color = if (exists) Color(0xFF2E7D32) else Color.Gray
                )
            }
        }
        Row {
            SelectionContainer {
                Text(
                    modifier = Modifier
                        .padding(3.dp, 3.dp)
                        .fillMaxWidth()
                        .background(Color(0xFFF5F5F5))
                        .padding(8.dp),
                    text = buildString {
                        r.eventId?.let { append("Event ID: ").append(it).append("\n") }
                        if (header.note.isNotEmpty()) append("Note: ").append(header.note).append("\n")
                        r.npub?.let { append("Author (npub): ").append(it).append("\n") }
                        r.createdAt?.let {
                            append("Created: ")
                                .append(SimpleDateFormat.getDateTimeInstance().format(Date(it * 1000)))
                                .append("\n")
                        }
                        r.mediaHash?.let { append("Media SHA-256: ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    Row {
        Text(modifier = Modifier.padding(2.dp, 2.dp), text = "")
    }
}

@Composable
fun CidManifestRow(
    label: String,
    tapHint: String,
    mediaHash: String,
    identifier: String,
    storageProvider: DefaultStorageProvider,
) {
    ExpandableArtifactJsonRow(
        label = label,
        collapsedHint = tapHint,
        mediaHash = mediaHash,
        identifier = identifier,
        storageProvider = storageProvider,
        trailingAction = { rawJson ->
            ProofOverviewArtifactSummaries.rootCidForArtifact(rawJson)?.let { rootCid ->
                CopyIconButton(
                    clipLabel = stringResource(R.string.root_cid_clip_label),
                    textToCopy = { rootCid },
                )
            }
        },
    )
}

@Composable
fun OffchainLpAttestationRow(
    label: String,
    tapHint: String,
    mediaHash: String,
    identifier: String,
    storageProvider: DefaultStorageProvider,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            // NonCancellable covers the copy and the failure toast: leaving the screen mid-save must
            // neither strand a truncated file at the picked location nor swallow the failure.
            withContext(NonCancellable) {
                val saved = withContext(Dispatchers.IO) {
                    copyStoredArtifact(storageProvider, mediaHash, identifier) {
                        context.contentResolver.openOutputStream(uri, "wt")
                    }
                }
                if (!saved) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.artifact_save_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    ExpandableArtifactJsonRow(
        label = label,
        collapsedHint = tapHint,
        mediaHash = mediaHash,
        identifier = identifier,
        storageProvider = storageProvider,
        trailingAction = {
            IconButton(onClick = { createDocument.launch(identifier) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_download),
                    contentDescription = stringResource(R.string.artifact_download_action),
                )
            }
            IconButton(
                onClick = {
                    shareArtifact(context, identifier) {
                        artifactShareUri(context, storageProvider, mediaHash, identifier)
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = stringResource(R.string.artifact_share_action),
                )
            }
        },
    )
}

// Hands the stored sidecar to the chooser by FileProvider URI so the recipient reads the bytes on
// disk, not the formatted display String. getUriForFile throws for a path outside the provider
// roots; shareArtifact's catch turns that into the failure toast.
internal fun artifactShareUri(
    context: Context,
    storageProvider: DefaultStorageProvider,
    hash: String,
    identifier: String,
): Uri {
    val file = File(checkNotNull(storageProvider.getHashStorageDir(hash)), identifier)
    return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
}

private fun shareArtifact(context: Context, identifier: String, uriProvider: () -> Uri) {
    try {
        val uri = uriProvider()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            // The chooser's targets get the read grant only through ClipData, not the extra alone.
            clipData = ClipData.newRawUri(identifier, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null))
    } catch (e: Exception) {
        Timber.e(e, "Error sharing artifact $identifier")
        Toast.makeText(context, R.string.artifact_share_failed, Toast.LENGTH_SHORT).show()
    }
}

// Streams the stored sidecar bytes as-is. The row's display String is formatted and decoded, so
// saving from it would change bytes a verifier outside the app expects unchanged. The output is
// opened only after the input exists, so a missing sidecar never truncates the picked file.
internal fun copyStoredArtifact(
    storageProvider: StorageProvider,
    hash: String,
    identifier: String,
    openOutput: () -> OutputStream?,
): Boolean {
    try {
        val input = storageProvider.getInputStream(hash, identifier) ?: return false
        input.use {
            val output = openOutput() ?: return false
            output.use { input.copyTo(it) }
        }
        return true
    } catch (e: Exception) {
        Timber.e(e, "Error saving artifact $identifier")
        return false
    }
}

private const val COPIED_CHECK_MILLIS = 2_000L

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnchainLpAttestationRow(
    label: String,
    tapHint: String,
    mediaHash: String,
    identifier: String,
    storageProvider: DefaultStorageProvider,
) {
    val context = LocalContext.current
    ExpandableArtifactJsonRow(
        label = label,
        collapsedHint = tapHint,
        mediaHash = mediaHash,
        identifier = identifier,
        storageProvider = storageProvider,
        expandedFooter = { rawJson ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProofOverviewArtifactSummaries.easScanUrlForArtifact(rawJson)?.let { url ->
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                        }
                    ) {
                        Text(text = stringResource(R.string.view_on_eas_scan))
                    }
                }
                ProofOverviewArtifactSummaries.transactionUrlForArtifact(rawJson)?.let { url ->
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                        }
                    ) {
                        Text(text = stringResource(R.string.view_transaction))
                    }
                }
            }
        },
        trailingAction = { rawJson ->
            // Copies the formatted text the row displays, not the stored string, so a paste matches the screen.
            CopyIconButton(
                clipLabel = stringResource(R.string.onchain_location_attestation),
                textToCopy = { ProofOverviewArtifactSummaries.formatArtifactJson(rawJson) },
            )
        },
    )
}

// The row composes its trailing action only while expanded, so collapsing within the check
// interval resets the icon to copy. That loss is harmless: the checkmark is only tap feedback,
// and the clipboard write has already happened.
@Composable
internal fun CopyIconButton(clipLabel: String, textToCopy: () -> String) {
    val context = LocalContext.current
    var copyTick by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    // A new tap changes the key, which cancels the pending reset and restarts the interval.
    LaunchedEffect(copyTick) {
        if (copyTick == 0) return@LaunchedEffect
        copied = true
        delay(COPIED_CHECK_MILLIS)
        copied = false
    }
    IconButton(onClick = {
        copyTextToClipboard(context, clipLabel, textToCopy())
        copyTick++
    }) {
        if (copied) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(R.string.artifact_copied),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_content_copy),
                contentDescription = stringResource(R.string.artifact_copy_action),
            )
        }
    }
}

// From API 33 the system shows its own clipboard notice, and a second toast would double it.
internal fun copyTextToClipboard(
    context: Context,
    clipLabel: String,
    text: String,
    sdkInt: Int = Build.VERSION.SDK_INT,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(clipLabel, text))
    if (sdkInt <= Build.VERSION_CODES.S_V2) {
        Toast.makeText(context, R.string.artifact_copied, Toast.LENGTH_SHORT).show()
    }
}

internal fun artifactSummaryLine(chainName: String?, hint: String): String =
    if (chainName != null) "$chainName — $hint" else hint

@Composable
private fun ExpandableArtifactJsonRow(
    label: String,
    collapsedHint: String,
    mediaHash: String,
    identifier: String,
    storageProvider: DefaultStorageProvider,
    expandedFooter: @Composable ((String) -> Unit)? = null,
    trailingAction: @Composable ((String) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var rawJson by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val onTap: () -> Unit = {
        if (!expanded && rawJson == null && !loading) {
            loading = true
            coroutineScope.launch {
                rawJson = withContext(Dispatchers.IO) {
                    try {
                        storageProvider.getInputStream(mediaHash, identifier)
                            ?.bufferedReader()?.use { it.readText() }
                    } catch (e: Exception) {
                        Timber.e(e, "Error reading artifact $identifier")
                        null
                    }
                }
                loading = false
                expanded = true
            }
        } else {
            expanded = !expanded
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
    ) {
        Row {
            Text(
                modifier = Modifier.padding(3.dp, 3.dp),
                text = label,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                modifier = Modifier
                    .weight(1f)
                    .padding(3.dp, 3.dp),
                text = artifactSummaryLine(rawJson?.let(ProofOverviewArtifactSummaries::chainDisplayName), collapsedHint),
                color = Color.Gray,
            )
            rawJson?.let { json ->
                if (expanded) trailingAction?.invoke(json)
            }
        }
        if (loading) {
            Row {
                Text(
                    modifier = Modifier.padding(3.dp, 3.dp),
                    text = stringResource(R.string.artifact_loading),
                    color = Color.Gray,
                )
            }
        }
        if (expanded && rawJson != null) {
            expandedFooter?.invoke(rawJson!!)
            Row {
                SelectionContainer {
                    Text(
                        modifier = Modifier
                            .padding(3.dp, 3.dp)
                            .fillMaxWidth()
                            .background(Color(0xFFF5F5F5))
                            .padding(8.dp),
                        text = ProofOverviewArtifactSummaries.formatArtifactJson(rawJson!!),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Row { Text(modifier = Modifier.padding(2.dp, 2.dp), text = "") }
    }
}

private fun mimeTypeForPath(path: String): String {
    val ext = path.substringAfterLast('.', "").lowercase(Locale.US)
    return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/jpeg"
}

private data class NostrHeader(
    val eventId: String,
    val note: String,
    val published: Boolean?,
    val relays: List<String>
)

private val DEFAULT_VERIFY_RELAYS = listOf(
    "wss://relay.damus.io",
    "wss://nos.lol",
    "wss://relay.nostr.band"
)

@Composable
fun addRow (key : String,  hmap: HashMap<String,String>?) {

    hmap?.get(key)?.let { it1 ->
        Row {
            Text(modifier = Modifier.padding(3.dp, 3.dp), text = "$key", fontWeight = FontWeight.Bold)
        }
        Row {
            SelectionContainer() { Text(modifier = Modifier.padding(3.dp, 3.dp), text = "${it1}") }
        }
        Row {
            Text(modifier = Modifier.padding(2.dp, 2.dp), text = "")

        }
    }


}

private fun convertDegMinsSecs(latitude: Double, longitude: Double): String? {
    val builder = StringBuilder()
    val latitudeDegrees = Location.convert(Math.abs(latitude), Location.FORMAT_SECONDS)
    val latitudeSplit = latitudeDegrees.split(":".toRegex()).dropLastWhile { it.isEmpty() }
        .toTypedArray()
    builder.append(latitudeSplit[0])
    builder.append("°")
    builder.append(latitudeSplit[1])
    builder.append("'")
    builder.append(latitudeSplit[2])
    builder.append("\"")
    if (latitude < 0) {
        builder.append("S ")
    } else {
        builder.append("N ")
    }
    builder.append("  ")
    val longitudeDegrees = Location.convert(Math.abs(longitude), Location.FORMAT_SECONDS)
    val longitudeSplit = longitudeDegrees.split(":".toRegex()).dropLastWhile { it.isEmpty() }
        .toTypedArray()
    builder.append(longitudeSplit[0])
    builder.append("°")
    builder.append(longitudeSplit[1])
    builder.append("'")
    builder.append(longitudeSplit[2])
    builder.append("\"")
    if (longitude < 0) {
        builder.append("W ")
    } else {
        builder.append("E ")
    }
    return builder.toString()
}

@Composable
fun addRow (key : String,  value: String) {

    Row {
        Text(modifier = Modifier.padding(3.dp, 3.dp), text = "$key", fontWeight = FontWeight.Bold)

    }
    Row {
        SelectionContainer() { Text(modifier = Modifier.padding(3.dp, 3.dp), text = "${value}") }
    }
    Row {
        Text(modifier = Modifier.padding(3.dp, 3.dp), text = "")

    }


}

@Composable
fun SingleItemView(itemWidth: Dp, allAssets: List<ProofableItem>, index: Int, selectedIndex: Int) {
    val context = LocalContext.current
    if (index >= 0 && index < allAssets.size) {
        val item = allAssets[index]
        Box(
            modifier = Modifier
                .requiredWidth(itemWidth)
                .fillMaxHeight()
        ) {

            val isVideo = remember(item) {
                isVideoItem(item.uri, context.contentResolver)
            }
            if (isVideo) {
                VideoItemView(item = item,modifier=Modifier.fillMaxSize())
            }
            else {

                ProofItemMedia(
                        item = item,
                        modifier = Modifier.fillMaxSize(),
                        // Always contain (Fit) in the full-screen viewer so the asset is
                        // shown at its true aspect ratio. The selection-driven crop only
                        // makes sense for the square grid thumbnails — here it would
                        // center-crop a 1:1 / 16:9 capture whenever the item was selected.
                        contain = true,
                        corners = RectF(0f, 0f, 0f, 0f),
                        zoomable = true,
                )
                if (LocalSelectionHandler.current.isSelected(item)) {
                    Box(modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)) {
                        Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Selected",
                                tint = Color(0.3f, 0.6f, 1.0f),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .width(16.dp)
                                    .height(16.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                        )
                    }
                }

            }
        }
    } else {
        Box(
            modifier = Modifier
                .width(itemWidth)
        )
    }
}

/**
 * A view for displaying a video
 */
@Composable
fun VideoItemView(item: ProofableItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val videoView = remember {
        VideoView(context)
    }
    val mediaController = remember {
        MediaController(context)
    }

    LaunchedEffect(key1 = item.uri) {
        videoView.apply {
            setMediaController(mediaController)
            mediaController.setAnchorView(this)
            setVideoURI(item.uri)
            post {
                seekTo(100)
            }
            //start()
        }
    }

    DisposableEffect(key1 = videoView) {
        onDispose {
            if (videoView.isPlaying) {
                videoView.stopPlayback()
            }
            mediaController.hide()
        }
    }

    AndroidView(
            factory = { videoView },
            modifier = modifier
    )
}

@Preview
@Composable
fun SingleAssetViewPreview() {
    SingleAssetView(initialItem = ProofableItem(id = "1", uri = Uri.parse("")), setShowMetadata = {}, setTitle = {} )
}
