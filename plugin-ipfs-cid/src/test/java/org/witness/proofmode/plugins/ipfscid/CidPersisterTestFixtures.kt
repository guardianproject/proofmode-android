package org.witness.proofmode.plugins.ipfscid

import android.content.Context
import android.net.Uri
import org.witness.proofmode.plugin.ProofWriteEvent
import org.witness.proofmode.storage.DefaultStorageProvider
import org.witness.proofmode.storage.StorageProvider
import java.io.File
import java.util.concurrent.ExecutorService

fun setupProofSetDir(context: Context, hash: String): File {
    val dir = DefaultStorageProvider(context).getHashStorageDir(hash)!!
    dir.mkdirs()
    File(dir, "$hash.proof.csv").writeText("col1,col2\nval1,val2\n")
    File(dir, "$hash.proof.json").writeText("""{"k":"v"}""")
    File(dir, "$hash.asc").writeText("-----BEGIN PGP SIGNATURE-----\n")
    return dir
}

fun resetSidecarWriterTestState() {
    ProofSetCidSidecarWriter.resetSidecarWriterTestState()
    IpfsCidPlugin.clearRegistrationStateForTests()
}

fun proofWriteEvent(
    context: Context,
    hash: String,
    mediaFile: File,
    storageProvider: StorageProvider,
    executor: ExecutorService,
    mediaUri: Uri = Uri.fromFile(mediaFile),
) = ProofWriteEvent(
    context = context,
    mediaHash = hash,
    mediaUri = mediaUri,
    storageProvider = storageProvider,
    executor = executor,
)
