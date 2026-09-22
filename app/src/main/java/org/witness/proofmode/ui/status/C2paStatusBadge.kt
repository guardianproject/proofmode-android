package org.witness.proofmode.ui.status

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.net.toFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.witness.proofmode.R
import org.witness.proofmode.c2pa.C2PAManager
import org.witness.proofmode.c2pa.PreferencesManager
import org.witness.proofmode.c2pa.ValidationState
import org.witness.proofmode.service.MediaWatcher
import org.witness.proofmode.ui.ProofStatus
import org.witness.proofmode.ui.ProofableItem

/**
 * Cache for C2PA verification results to avoid repeated expensive checks.
 * Results are cached for the lifespan of the process.
 */
private object C2PAVerificationCache {
    private val cache = mutableMapOf<String, ValidationState>()

    fun get(path: String): ValidationState? = cache[path]

    fun put(path: String, state: ValidationState) {
        cache[path] = state
    }

    fun clear() {
        cache.clear()
    }
}

@Composable
internal fun rememberC2paStatus(item: ProofableItem): ValidationState {
    val context = LocalContext.current
    var state by remember(item.uri, item.proofStatus) { mutableStateOf(ValidationState.INVALID) }

    LaunchedEffect(item.uri, item.proofStatus) {
        if (item.proofStatus != ProofStatus.GENERATED) {
            state = ValidationState.INVALID
            return@LaunchedEffect
        }

        val filePath = if (item.uri.scheme == "file") {
            item.uri.toFile().canonicalPath
        } else {
            val imagePath = MediaWatcher.getImagePath(context, item.uri)
            if (imagePath?.isEmpty() == true) {
                MediaWatcher.getVideoPath(context, item.uri)
            } else {
                imagePath
            }
        }

        filePath?.let { path ->
            val cachedResult = C2PAVerificationCache.get(path)
            if (cachedResult != null) {
                state = cachedResult
            } else {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val c2paMan = C2PAManager(context, PreferencesManager(context))
                        c2paMan.validateSignedMedia(path)
                    } catch (e: Exception) {
                        ValidationState.INVALID
                    }
                }
                C2PAVerificationCache.put(path, result)
                state = result
            }
        }
    }

    return state
}

@Composable
internal fun BoxScope.C2paStatusBadge(badgeLayout: BadgeLayout) {
    Image(
        painter = painterResource(R.drawable.cricon),
        contentDescription = "CR",
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(badgeLayout.inset)
            .size(badgeLayout.iconSize),
    )
}
