package org.witness.proofmode.org.witness.proofmode.share.p2p

import android.net.wifi.WifiManager
import android.os.Bundle
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import org.witness.proofmode.R
import org.witness.proofmode.databinding.ActivityP2PshareBinding
import java.util.concurrent.CompletableFuture.runAsync

class P2PShareActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityP2PshareBinding

    private lateinit var multicastLock: WifiManager.MulticastLock
    private lateinit var chatNode: ChatNode

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityP2PshareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        binding.fab.setOnClickListener { view ->
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                .setAction("Action", null).show()
        }

        runAsync {
            acquireMulticastLock()

            chatNode = ChatNode(::chatMessage)

        }
    }

    private fun chatMessage(msg: String) {
        runOnUiThread {
          //  chatWindow.append(msg)
        }
    } //


    private fun acquireMulticastLock() {
        val wifi = getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("libp2p-chatter")
        multicastLock.acquire()
    }

    private fun releaseMulticastLock() {
        multicastLock.release()
    }
}