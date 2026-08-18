package com.yuji.transcriber

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SearchActivity : AppCompatActivity() {

    private lateinit var queryInput: EditText
    private lateinit var searchButton: Button
    private lateinit var resultList: ListView
    private lateinit var resultCountText: TextView
    private var results: List<SearchResult> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        queryInput = findViewById(R.id.queryInput)
        searchButton = findViewById(R.id.searchButton)
        resultList = findViewById(R.id.resultList)
        resultCountText = findViewById(R.id.resultCountText)

        searchButton.setOnClickListener { runSearch() }
        resultList.setOnItemClickListener { _, _, position, _ ->
            val result = results[position]
            val intent = Intent(this, TranscriptViewActivity::class.java).apply {
                putExtra(TranscriptViewActivity.EXTRA_FILE_PATH, result.entry.file.absolutePath)
                putExtra(TranscriptViewActivity.EXTRA_QUERY, queryInput.text.toString().trim())
            }
            startActivity(intent)
        }
    }

    private fun runSearch() {
        val query = queryInput.text.toString().trim()
        results = SearchEngine.search(query)
        resultCountText.text = if (query.isEmpty()) {
            ""
        } else {
            "找到 ${results.size} 条结果"
        }
        resultList.adapter = ResultAdapter(results)
    }

    private inner class ResultAdapter(private val items: List<SearchResult>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@SearchActivity)
                .inflate(R.layout.item_search_result, parent, false)
            val item = items[position]
            view.findViewById<TextView>(R.id.itemTitle).text = item.entry.title
            view.findViewById<TextView>(R.id.itemDate).text = item.entry.date
            view.findViewById<TextView>(R.id.itemSnippet).text = item.snippet
            return view
        }
    }
}
