package com.example.kellerkino

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import org.json.JSONArray
import org.json.JSONObject

internal object KellerkinoCatalogStore {
    data class Movie(val title: String, val url: String, val poster: String?)
    data class Row(val title: String, val movies: List<Movie>)
    data class Catalog(val updatedAt: Long, val rows: List<Row>)

    private const val KEY = "kellerkino_catalog_v1"

    fun load(): Catalog? = runCatching {
        val root = JSONObject(getKey<String>(KEY) ?: return null)
        val rows = root.getJSONArray("rows")
        Catalog(root.getLong("updatedAt"), (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val movies = row.getJSONArray("movies")
            Row(row.getString("title"), (0 until movies.length()).map { j ->
                val movie = movies.getJSONObject(j)
                Movie(movie.getString("title"), movie.getString("url"),
                    movie.optString("poster").takeIf { it.isNotBlank() })
            })
        }).takeIf { catalog -> catalog.rows.any { it.movies.isNotEmpty() } }
    }.getOrNull()

    fun save(catalog: Catalog) {
        val rows = JSONArray()
        catalog.rows.forEach { row ->
            val movies = JSONArray()
            row.movies.forEach { movie ->
                movies.put(JSONObject().put("title", movie.title).put("url", movie.url)
                    .put("poster", movie.poster ?: ""))
            }
            rows.put(JSONObject().put("title", row.title).put("movies", movies))
        }
        setKey(KEY, JSONObject().put("updatedAt", catalog.updatedAt).put("rows", rows).toString())
    }
}
