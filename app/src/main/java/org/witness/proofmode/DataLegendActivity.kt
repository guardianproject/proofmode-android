package org.witness.proofmode

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import org.witness.proofmode.databinding.ActivityDataLegendBinding

class DataLegendActivity : AppCompatActivity() {
    private lateinit var binding:ActivityDataLegendBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this,R.layout.activity_data_legend)
        binding.lifecycleOwner = this
        val toolbar = binding.toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        val title = binding.toolbarTitle
        title.text = getTitle()
        val webView = binding.webView
        val webSetting = webView.settings
        webSetting.builtInZoomControls = false
        webSetting.javaScriptEnabled = false
        webView.webViewClient = WebViewClient()
        webView.loadUrl("file:///android_asset/datalegend/datalegend.html")
    }

    private inner class WebViewClient : android.webkit.WebViewClient()

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