package org.witness.proofmode

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import org.witness.proofmode.databinding.ActivityDataLegendBinding

class DataLegendActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDataLegendBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDataLegendBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            setDisplayShowTitleEnabled(false)
        }
        val webView = binding.webView
        val webSetting = webView.settings
        webSetting.apply {
            builtInZoomControls = false
            javaScriptEnabled = true
        }
        webView.apply {
            webViewClient = android.webkit.WebViewClient()
            loadUrl("file:///android_asset/datalegend/datalegend.html")
        }
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        val id = item.itemId
        if (id == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}