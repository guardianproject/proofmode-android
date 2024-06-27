package org.witness.proofmode.org.witness.proofmode.share.p2p

import org.witness.proofmode.notarization.NotarizationListener
import org.witness.proofmode.notarization.NotarizationProvider
import java.io.InputStream

class P2PNotarizationProvider : NotarizationProvider {

    private lateinit var chatNode: ChatNode
    private lateinit var notarizationListener : NotarizationListener

    init {
        chatNode = ChatNode(::chatMessage)
    }

    private fun chatMessage(msg: String) {

        var hashResult = msg.split(":")

        notarizationListener?.notarizationSuccessful(hashResult[0], hashResult[1])
    }

    override fun notarize(
        hash: String?,
        mimeType: String?,
        `is`: InputStream?,
        listener: NotarizationListener?
    ) {
        hash?.let {
            chatNode.send(it)
        }

    }

    override fun getProof(hash: String?): String? {
        return null
    }

    override fun getNotarizationFileExtension(): String {
        return ".p2p"
    }
}