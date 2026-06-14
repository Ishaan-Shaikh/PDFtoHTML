package com.example.pdftohtml

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.pdftohtml.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import java.io.File

data class TocEntry(val title: String, val pageNumber: Int)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var selectedPdfUri: Uri? = null
    private var generatedHtmlFile: File? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var drawerLayout: androidx.drawerlayout.widget.DrawerLayout

    private var currentFontSize = 18
    private var currentTheme = "white"

    private val tocEntries = mutableListOf<TocEntry>()

    companion object {
        private const val PICK_PDF_REQUEST = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        drawerLayout = binding.drawerLayout

        PDFBoxResourceLoader.init(applicationContext)

        setSupportActionBar(binding.toolbar)

        binding.btnPickPdf.setOnClickListener { openFilePicker() }
        binding.btnConvert.setOnClickListener { startConversion() }

        setupWebView()

        var lastTouchTime = 0L
        var lastTouchX = 0f
        var lastTouchY = 0f

        binding.btnShare.setOnClickListener { shareHtmlFile() }

        binding.btnToc.setOnClickListener {
            buildTocDrawer()
            binding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
        }

        binding.btnSearch.setOnClickListener {
            binding.searchBar.visibility = View.VISIBLE
            binding.etSearch.requestFocus()
        }

        binding.btnCloseSearch.setOnClickListener {
            binding.searchBar.visibility = View.GONE
            binding.etSearch.text.clear()
            clearSearch()
        }

        binding.btnNextResult.setOnClickListener { nextSearchResult() }
        binding.btnPrevResult.setOnClickListener { prevSearchResult() }

        binding.etSearch.setOnEditorActionListener { _, _, _ ->
            searchInBook(binding.etSearch.text.toString())
            true
        }

        binding.webView.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastTouchTime = System.currentTimeMillis()
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - lastTouchTime
                    val dx = Math.abs(event.x - lastTouchX)
                    val dy = Math.abs(event.y - lastTouchY)
                    val screenHeight = binding.webView.height
                    val tapY = event.y
                    val middle = screenHeight / 2
                    val threshold = screenHeight / 4

                    // Only trigger if it was a real tap (not a scroll)
                    if (duration < 300 && dx < 20 && dy < 20 &&
                        tapY > (middle - threshold) && tapY < (middle + threshold)) {
                        showDisplaySettingsDialog()
                    }
                }
            }
            false
        }
    }

    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(true)
            textZoom = 100
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Restore scroll position for this specific PDF
                val prefs = getSharedPreferences("reading_prefs", MODE_PRIVATE)
                val key = "scroll_${selectedPdfUri?.lastPathSegment}"
                val scrollY = prefs.getInt(key, 0)
                if (scrollY > 0) {
                    binding.webView.postDelayed({
                        binding.webView.scrollTo(0, scrollY)
                    }, 300)
                }
            }
        }
    }

    private fun saveScrollPosition() {
        val uri = selectedPdfUri ?: return
        val prefs = getSharedPreferences("reading_prefs", MODE_PRIVATE)
        val key = "scroll_${uri.lastPathSegment}"
        prefs.edit().putInt(key, binding.webView.scrollY).apply()
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, PICK_PDF_REQUEST)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_PDF_REQUEST && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                selectedPdfUri = uri
                val name = uri.lastPathSegment ?: "selected file"
                binding.tvFileName.text = name
                binding.btnConvert.isEnabled = true
                binding.tvStatus.text = "Ready to convert"
            }
        }
    }

    private fun startConversion() {
        val uri = selectedPdfUri ?: return
        binding.btnConvert.isEnabled = false
        binding.btnPickPdf.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvStatus.text = "Starting conversion..."

        scope.launch {
            try {
                val htmlFile = withContext(Dispatchers.IO) {
                    convertPdfToHtml(uri) { page, total ->
                        launch(Dispatchers.Main) {
                            val pct = ((page.toFloat() / total) * 100).toInt()
                            binding.progressBar.progress = pct
                            binding.tvStatus.text = "Converting page $page of $total..."
                        }
                    }
                }
                generatedHtmlFile = htmlFile
                binding.progressBar.progress = 100
                loadHtmlInWebView(htmlFile)

// Hide all top UI to give full screen reading experience
                // Hide all top UI to give full screen reading experience
                binding.btnPickPdf.visibility = View.GONE
                binding.btnConvert.visibility = View.GONE
                binding.tvFileName.visibility = View.GONE
                binding.tvStatus.visibility = View.GONE
                binding.progressBar.visibility = View.GONE
                binding.btnShare.visibility = View.VISIBLE
                binding.btnToc.visibility = View.VISIBLE
                binding.btnSearch.visibility = View.VISIBLE
                loadHtmlInWebView(htmlFile)
            } catch (e: Exception) {
                binding.tvStatus.text = "❌ Error: ${e.message}"
                Toast.makeText(this@MainActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnConvert.isEnabled = true
                binding.btnPickPdf.isEnabled = true
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun convertPdfToHtml(
        uri: Uri,
        onProgress: suspend (Int, Int) -> Unit
    ): File {
        val inputStream = contentResolver.openInputStream(uri)
            ?: throw Exception("Cannot open PDF")

        val document = PDDocument.load(inputStream)
        val totalPages = document.numberOfPages
        val stripper = PDFTextStripper()
        val sb = StringBuilder()

        sb.append(buildHtmlHeader())

        for (page in 1..totalPages) {
            stripper.startPage = page
            stripper.endPage = page
            val text = stripper.getText(document)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .trim()

            if (text.isNotEmpty()) {
                detectTocEntries(text, page)
                sb.append("<div class='page' id='page-$page'>")
                sb.append("<span class='page-num'>— Page $page —</span>")
                val paragraphs = text.split("\n\n")
                for (para in paragraphs) {
                    val line = para.replace("\n", " ").trim()
                    if (line.isNotEmpty()) {
                        sb.append("<p>$line</p>")
                    }
                }
                sb.append("</div>")
            }

            runBlocking { onProgress(page, totalPages) }
        }

        sb.append(buildHtmlFooter())
        document.close()
        inputStream.close()

        val outDir = File(filesDir, "html_output").also { it.mkdirs() }
        val outFile = File(outDir, "book.html")
        outFile.writeText(sb.toString())
        return outFile
    }

    private fun detectTocEntries(pageText: String, pageNumber: Int) {
        val lines = pageText.split("\n")
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val isChapterHeading = trimmed.contains(Regex(
                "^(chapter|part|section|unit|book|volume|appendix)\\s*[\\d\\w]+",
                RegexOption.IGNORE_CASE
            ))

            val isNumberedHeading = trimmed.matches(Regex(
                "^(\\d+|[ivxlcdmIVXLCDM]+)[.:\\-)\\s].{3,50}"
            ))

            val isShortAllCaps = trimmed.length in 3..60
                    && trimmed == trimmed.uppercase()
                    && trimmed.any { it.isLetter() }

            val isFallback = pageNumber % 50 == 0

            if (isChapterHeading || isNumberedHeading || isShortAllCaps || isFallback) {
                val title = if (isFallback && !isChapterHeading && !isNumberedHeading && !isShortAllCaps) {
                    "Page $pageNumber"
                } else {
                    trimmed.take(60)
                }
                // Avoid duplicate entries for same page
                if (tocEntries.none { it.pageNumber == pageNumber }) {
                    tocEntries.add(TocEntry(title, pageNumber))
                }
                break
            }
        }
    }

    private fun buildTocDrawer() {
        val container = findViewById<LinearLayout>(R.id.tocContainer)
        // Remove all views except the title (first child)
        if (container.childCount > 1) {
            container.removeViews(1, container.childCount - 1)
        }

        if (tocEntries.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No chapters detected."
                textSize = 14f
                setPadding(0, 8, 0, 8)
            }
            container.addView(empty)
            return
        }

        tocEntries.forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
            }

            val title = TextView(this).apply {
                text = entry.title
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val pageNum = TextView(this).apply {
                text = "p.${entry.pageNumber}"
                textSize = 12f
                setTextColor(android.graphics.Color.GRAY)
            }

            row.addView(title)
            row.addView(pageNum)

            row.setOnClickListener {
                val js = "var el = document.getElementById('page-${entry.pageNumber}'); if(el) { el.scrollIntoView({behavior:'smooth', block:'start'}); }"
                binding.webView.evaluateJavascript(js, null)
                binding.drawerLayout.closeDrawers()
            }

            // Divider
            val divider = android.view.View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(android.graphics.Color.LTGRAY)
            }

            container.addView(row)
            container.addView(divider)
        }
    }

    private fun buildHtmlHeader(): String = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="UTF-8"/>
          <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
          <title>Book</title>
          <style>
            :root { --bg:#ffffff; --text:#1a1a1a; --page-border:#e0e0e0; --num-color:#999; }
            body {
              background: var(--bg); color: var(--text);
              font-family: 'Georgia', serif; font-size: 18px;
              line-height: 1.8; max-width: 820px;
              margin: 0 auto; padding: 24px 16px 80px;
              word-wrap: break-word;
              transition: background 0.3s, color 0.3s, font-size 0.2s;
            }
            .page { border-bottom:1px solid var(--page-border); margin-bottom:24px; padding-bottom:16px; }
            .page-num { display:block; text-align:center; font-size:0.75em; color:var(--num-color); margin-bottom:12px; }
            p { margin:0 0 1em; text-align:justify; }
            body.sepia { --bg:#f8f1e3; --text:#3d2b1f; --page-border:#d4c5a9; --num-color:#a0856b; }
            body.dark  { --bg:#1e1e1e; --text:#e0e0e0; --page-border:#333;    --num-color:#777; }
            body.green { --bg:#1a2e1a; --text:#c8e6c9; --page-border:#2e4a2e; --num-color:#7cb87c; }
            body.navy  { --bg:#0a1628; --text:#cdd9f0; --page-border:#1a2e50; --num-color:#6a8fc0; }
          </style>
        </head>
        <body id="bookBody">
    """.trimIndent()

    private fun buildHtmlFooter(): String = """
        </body>
        </html>
    """.trimIndent()

    private fun loadHtmlInWebView(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        binding.webView.loadUrl(uri.toString())
    }

    private fun showDisplaySettingsDialog() {
        if (generatedHtmlFile == null) return
        AlertDialog.Builder(this)
            .setTitle("📖 Display Settings")
            .setView(buildSettingsView())
            .setPositiveButton("Close", null)
            .show()
    }

    private fun buildSettingsView(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val fontLabel = TextView(this).apply {
            text = "Font Size: ${currentFontSize}sp"
            textSize = 15f
        }
        layout.addView(fontLabel)

        val seekBar = SeekBar(this).apply {
            max = 24
            progress = currentFontSize - 12
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                currentFontSize = p + 12
                fontLabel.text = "Font Size: ${currentFontSize}sp"
                applyFontSize(currentFontSize)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        layout.addView(seekBar)

        layout.addView(Space(this).apply { minimumHeight = 24 })

        layout.addView(TextView(this).apply {
            text = "Background Theme:"
            textSize = 15f
        })

        val themes = listOf(
            "☀ White" to "white",
            "📜 Sepia" to "sepia",
            "🌙 Dark"  to "dark",
            "🌿 Green" to "green",
            "🌊 Navy"  to "navy"
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        themes.forEach { (label, key) ->
            val btn = MaterialButton(this).apply {
                text = label
                textSize = 11f
                setPadding(8, 4, 8, 4)
                setOnClickListener { applyTheme(key) }
            }
            row.addView(btn, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        layout.addView(row)

        // Spacer
        layout.addView(Space(this).apply { minimumHeight = 24 })

        // Go to page label
        layout.addView(TextView(this).apply {
            text = "Go to Page:"
            textSize = 15f
        })

        // Input + Button row
        val pageRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val pageInput = android.widget.EditText(this).apply {
            hint = "Page number"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 15f
        }

        val goBtn = MaterialButton(this).apply {
            text = "Go"
            textSize = 13f
        }

        goBtn.setOnClickListener {
            val pageNum = pageInput.text.toString().toIntOrNull()
            if (pageNum != null && pageNum > 0) {
                val js = "var el = document.getElementById('page-$pageNum'); if(el) { el.scrollIntoView({behavior:'smooth', block:'start'}); }"
                binding.webView.evaluateJavascript(js, null)
            } else {
                Toast.makeText(this@MainActivity, "Enter a valid page number", Toast.LENGTH_SHORT).show()
            }
        }

        pageRow.addView(pageInput, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pageRow.addView(goBtn)
        layout.addView(pageRow)

        return layout
    }

    private fun applyFontSize(size: Int) {
        binding.webView.evaluateJavascript(
            "document.body.style.fontSize='${size}px';", null
        )
    }

    private fun applyTheme(theme: String) {
        binding.webView.evaluateJavascript(
            "document.body.className = '$theme' === 'white' ? '' : '$theme';", null
        )
    }

    override fun onPause() {
        super.onPause()
        saveScrollPosition()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveScrollPosition()
        scope.cancel()
    }

    private fun shareHtmlFile() {
        val file = generatedHtmlFile ?: run {
            Toast.makeText(this, "No HTML file yet. Convert a PDF first.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share HTML file via..."))
    }

    private fun searchInBook(query: String) {
        if (query.isEmpty()) return
        val js = """
        (function() {
            // Remove previous highlights
            var highlighted = document.querySelectorAll('.search-highlight');
            highlighted.forEach(function(el) {
                el.outerHTML = el.innerHTML;
            });
            
            if ('$query'.trim() === '') return;
            
            // Search and highlight
            var body = document.body;
            var regex = new RegExp('($query)', 'gi');
            body.innerHTML = body.innerHTML.replace(regex, 
                '<span class="search-highlight" style="background:#ffeb3b;color:#000;">$1</span>');
            
            // Jump to first result
            var first = document.querySelector('.search-highlight');
            if (first) first.scrollIntoView({behavior:'smooth', block:'center'});
        })();
    """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    private fun clearSearch() {
        val js = """
        (function() {
            var highlighted = document.querySelectorAll('.search-highlight');
            highlighted.forEach(function(el) {
                el.outerHTML = el.innerHTML;
            });
        })();
    """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    private fun nextSearchResult() {
        val js = """
        (function() {
            var results = document.querySelectorAll('.search-highlight');
            if (results.length === 0) return;
            var current = document.querySelector('.search-current');
            var idx = 0;
            if (current) {
                current.classList.remove('search-current');
                current.style.background = '#ffeb3b';
                idx = Array.from(results).indexOf(current) + 1;
                if (idx >= results.length) idx = 0;
            }
            results[idx].classList.add('search-current');
            results[idx].style.background = '#ff9800';
            results[idx].scrollIntoView({behavior:'smooth', block:'center'});
        })();
    """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    private fun prevSearchResult() {
        val js = """
        (function() {
            var results = document.querySelectorAll('.search-highlight');
            if (results.length === 0) return;
            var current = document.querySelector('.search-current');
            var idx = results.length - 1;
            if (current) {
                current.classList.remove('search-current');
                current.style.background = '#ffeb3b';
                idx = Array.from(results).indexOf(current) - 1;
                if (idx < 0) idx = results.length - 1;
            }
            results[idx].classList.add('search-current');
            results[idx].style.background = '#ff9800';
            results[idx].scrollIntoView({behavior:'smooth', block:'center'});
        })();
    """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }
}