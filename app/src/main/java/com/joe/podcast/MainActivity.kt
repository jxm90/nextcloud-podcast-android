package com.joe.podcast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.mediarouter.app.MediaRouteButton
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerNotificationManager
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.joe.podcast.databinding.ActivityMainBinding
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata as CastMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.common.api.PendingResult
import com.google.android.gms.common.api.ResultCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.File
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SettingsStore
    private lateinit var adapter: EpisodesAdapter
    private lateinit var showsAdapter: ShowsAdapter

    private var allEpisodes: List<Episode> = emptyList()
    private var allShows: List<PodcastShow> = emptyList()
    private var selectedShow: PodcastShow? = null
    private var currentEpisode: Episode? = null

    private var player: ExoPlayer? = null
    private var cache: SimpleCache? = null
    private var mediaSession: MediaSession? = null
    private var notificationManager: PlayerNotificationManager? = null
    private val discoveryClient = OkHttpClient()
    private var castContext: CastContext? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = SettingsStore(this)
        adapter = EpisodesAdapter(
            onPlay = { playEpisode(it) },
            onDownload = { downloadEpisode(it) },
            onTogglePlayed = { togglePlayed(it) },
            isDownloaded = { episodeFile(it.id).exists() },
            isPlayed = { prefs.isPlayed(it.id) }
        )
        showsAdapter = ShowsAdapter { show ->
            selectedShow = show
            showEpisodes(show)
        }
        showsAdapter.onLongPress = { show -> promptDeleteFeed(show) }

        binding.episodesRecycler.layoutManager = LinearLayoutManager(this)
        binding.episodesRecycler.adapter = showsAdapter

        setupActions()
        setupCast()
        binding.playerContainer.isVisible = false
        binding.playerView.isVisible = false
        updateStatus("Ready")
        maybeAutoLoad()
    }

    override fun onResume() {
        super.onResume()
        if (allShows.isEmpty()) maybeAutoLoad()
    }

    private fun setupActions() {
        binding.refreshButton.setOnClickListener { loadPodcasts() }
        binding.addFeedButton.setOnClickListener { promptAddFeed() }
        binding.discoverButton.setOnClickListener { promptDiscoverFeeds() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.backToShowsButton.setOnClickListener { showShowsList() }
    }

    private fun setupCast() {
        val castButton = findViewById<MediaRouteButton>(R.id.castButton)
        try {
            castContext = CastContext.getSharedInstance(this)
            CastButtonFactory.setUpMediaRouteButton(applicationContext, castButton)
        } catch (e: Exception) {
            castButton.isVisible = false
            Log.w("PodcastCast", "Cast unavailable", e)
        }
    }

    private fun promptAddFeed() {
        val cfg = prefs.configOrNull() ?: run {
            toast("Configure settings first")
            return
        }

        val input = EditText(this).apply {
            hint = "https://example.com/feed.xml"
        }

        AlertDialog.Builder(this)
            .setTitle("Add Podcast Feed")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val feedUrl = input.text?.toString()?.trim().orEmpty()
                if (feedUrl.isBlank()) {
                    toast("Feed URL is required")
                    return@setPositiveButton
                }

                setLoading(true)
                updateStatus("Adding feed…")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        SubsonicClient(cfg).createPodcastChannel(feedUrl)
                        withContext(Dispatchers.Main) {
                            toast("Feed added")
                            loadPodcasts()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            setLoading(false)
                            toast("Add failed: ${e.message}")
                            updateStatus("Add feed failed")
                        }
                    }
                }
            }
            .show()
    }

    private fun promptDiscoverFeeds() {
        val cfg = prefs.configOrNull() ?: run {
            toast("Configure settings first")
            return
        }

        val input = EditText(this).apply {
            hint = "Search podcast name or topic"
        }

        AlertDialog.Builder(this)
            .setTitle("Discover Podcasts")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Search") { _, _ ->
                val query = input.text?.toString()?.trim().orEmpty()
                if (query.isBlank()) {
                    toast("Search query is required")
                    return@setPositiveButton
                }

                setLoading(true)
                updateStatus("Searching podcasts…")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val results = discoverPodcastFeeds(query)
                        withContext(Dispatchers.Main) {
                            setLoading(false)
                            showDiscoveryResults(cfg, query, results)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            setLoading(false)
                            toast("Search failed: ${e.message}")
                            updateStatus("Search failed")
                        }
                    }
                }
            }
            .show()
    }

    private fun showDiscoveryResults(cfg: AppConfig, query: String, results: List<FeedSearchResult>) {
        if (results.isEmpty()) {
            toast("No podcast feeds found")
            updateStatus("No discovery results")
            return
        }

        val labels = results.map {
            val creator = it.author?.takeIf { a -> a.isNotBlank() } ?: "Unknown creator"
            "${it.title} — $creator"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Results for \"$query\"")
            .setItems(labels) { _, which ->
                importDiscoveredFeed(cfg, results[which])
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun importDiscoveredFeed(cfg: AppConfig, result: FeedSearchResult) {
        setLoading(true)
        updateStatus("Importing feed…")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SubsonicClient(cfg).createPodcastChannel(result.feedUrl)
                withContext(Dispatchers.Main) {
                    toast("Imported: ${result.title}")
                    loadPodcasts()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    toast("Import failed: ${e.message}")
                    updateStatus("Import failed")
                }
            }
        }
    }

    private fun discoverPodcastFeeds(query: String): List<FeedSearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=30&term=$encoded"
        val req = Request.Builder().url(url).get().build()

        discoveryClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("HTTP ${res.code}")
            val body = res.body?.string().orEmpty()
            val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()

            val out = mutableListOf<FeedSearchResult>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val feedUrl = item.optString("feedUrl").trim()
                if (feedUrl.isBlank()) continue
                val title = item.optString("collectionName").ifBlank { "Untitled podcast" }
                val author = item.optString("artistName").ifBlank { null }
                out += FeedSearchResult(title = title, author = author, feedUrl = feedUrl)
            }

            return out.distinctBy { it.feedUrl }
        }
    }

    private fun maybeAutoLoad() {
        if (prefs.configOrNull() == null) {
            updateStatus("Open Settings to add server login")
            return
        }
        loadPodcasts()
    }

    private fun loadPodcasts() {
        val cfg = prefs.configOrNull() ?: run {
            toast("Open Settings and save URL/username/app password")
            return
        }

        setLoading(true)
        updateStatus("Loading podcasts…")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val episodes = SubsonicClient(cfg).getPodcastEpisodes()
                withContext(Dispatchers.Main) {
                    allEpisodes = episodes
                    allShows = episodes.groupBy { it.podcastName }
                        .map { (title, list) ->
                            PodcastShow(
                                title,
                                list.sortedByDescending { it.publishedSortKey() },
                                list.firstOrNull()?.channelId
                            )
                        }
                        .sortedBy { it.title.lowercase() }
                    showShowsList()
                    updateStatus("Loaded ${allShows.size} shows / ${episodes.size} episodes")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast("Load failed: ${e.message}")
                    updateStatus("Load failed")
                }
            } finally {
                withContext(Dispatchers.Main) { setLoading(false) }
            }
        }
    }

    private fun showShowsList() {
        selectedShow = null
        binding.showHeaderRow.isVisible = false
        binding.episodesRecycler.adapter = showsAdapter
        showsAdapter.submit(allShows)
        binding.emptyText.isVisible = allShows.isEmpty()
        if (allShows.isEmpty()) binding.emptyText.text = "No shows found"
    }

    private fun showEpisodes(show: PodcastShow) {
        binding.showHeaderRow.isVisible = true
        binding.showTitleText.text = show.title
        binding.episodesRecycler.adapter = adapter
        adapter.submit(show.episodes)
        binding.emptyText.isVisible = show.episodes.isEmpty()
        if (show.episodes.isEmpty()) binding.emptyText.text = "No episodes found"
    }

    private fun promptDeleteFeed(show: PodcastShow) {
        val cfg = prefs.configOrNull() ?: return
        val channelId = show.channelId
        if (channelId.isNullOrBlank()) {
            toast("This show cannot be deleted")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Delete feed")
            .setMessage("Delete \"${show.title}\"?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                setLoading(true)
                updateStatus("Deleting feed…")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        SubsonicClient(cfg).deletePodcastChannel(channelId)
                        withContext(Dispatchers.Main) {
                            toast("Feed deleted")
                            loadPodcasts()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            setLoading(false)
                            toast("Delete failed: ${e.message}")
                            updateStatus("Delete failed")
                        }
                    }
                }
            }
            .show()
    }

    private fun playEpisode(episode: Episode) {
        val cfg = prefs.configOrNull() ?: run {
            toast("Configure settings first")
            return
        }

        saveCurrentProgress()
        currentEpisode = episode

        val castSession = castContext?.sessionManager?.currentCastSession
        if (castSession != null && castSession.isConnected) {
            val resume = prefs.resumeMs(episode.id)
            if (castEpisodeToDevice(castSession, cfg, episode, resume)) return
        }

        val localFile = episodeFile(episode.id)
        val mediaUri = if (localFile.exists()) Uri.fromFile(localFile).toString()
        else SubsonicClient(cfg).buildStreamUrl(episode.id)

        val exo = getOrCreatePlayer()
        binding.playerContainer.isVisible = true
        binding.playerView.isVisible = true
        val mediaItem = MediaItem.Builder()
            .setUri(mediaUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(episode.podcastName)
                    .build()
            )
            .build()
        exo.setMediaItem(mediaItem)
        exo.prepare()

        val resume = prefs.resumeMs(episode.id)
        if (resume > 0) exo.seekTo(resume)

        exo.playWhenReady = true
        updateStatus("Playing: ${episode.title}${if (localFile.exists()) " (offline)" else ""}")
    }

    private fun castEpisodeToDevice(session: CastSession, cfg: AppConfig, episode: Episode, startMs: Long): Boolean {
        val remoteClient = session.remoteMediaClient ?: run {
            toast("Cast device not ready")
            return false
        }

        return try {
            val metadata = CastMetadata(CastMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                putString(CastMetadata.KEY_TITLE, episode.title)
                putString(CastMetadata.KEY_ARTIST, episode.podcastName)
            }

            val client = SubsonicClient(cfg)
            val streamUrl = client.buildStreamUrl(episode.id)
            val downloadUrl = client.buildDownloadUrl(episode.id)

            val firstRequest = buildCastLoadRequest(streamUrl, metadata, startMs)
            val pending = remoteClient.load(firstRequest)

            pending.setResultCallback { result ->
                if (result.status.isSuccess) {
                    runOnUiThread {
                        updateStatus("Casting: ${episode.title}")
                    }
                } else {
                    val fallbackRequest = buildCastLoadRequest(downloadUrl, metadata, startMs)
                    val fallbackPending = remoteClient.load(fallbackRequest)
                    fallbackPending.setResultCallback { fallback ->
                        runOnUiThread {
                            if (fallback.status.isSuccess) {
                                updateStatus("Casting (download): ${episode.title}")
                            } else {
                                val code = fallback.status.statusCode
                                val msg = fallback.status.statusMessage ?: "Unknown cast error"
                                updateStatus("Cast failed ($code)")
                                toast("Cast failed ($code): $msg")
                            }
                        }
                    }
                }
            }

            player?.pause()
            binding.playerContainer.isVisible = true
            binding.playerView.isVisible = false
            updateStatus("Casting request sent…")
            true
        } catch (e: Exception) {
            toast("Cast failed: ${e.message}")
            false
        }
    }

    private fun buildCastLoadRequest(url: String, metadata: CastMetadata, startMs: Long): MediaLoadRequestData {
        val contentType = when {
            url.contains("format=mp3", ignoreCase = true) -> "audio/mpeg"
            url.contains("format=m4a", ignoreCase = true) -> "audio/mp4"
            else -> "audio/*"
        }
        val mediaInfo = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(contentType)
            .setMetadata(metadata)
            .build()

        return MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .setCurrentTime(startMs.coerceAtLeast(0L))
            .build()
    }

    private fun downloadEpisode(episode: Episode) {
        val cfg = prefs.configOrNull() ?: run {
            toast("Configure settings first")
            return
        }

        updateStatus("Downloading: ${episode.title}")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = SubsonicClient(cfg)
                val req = Request.Builder().url(client.buildDownloadUrl(episode.id)).get().build()
                val out = episodeFile(episode.id)
                out.parentFile?.mkdirs()
                client.rawClient.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) error("HTTP ${res.code}")
                    val body = res.body ?: error("Empty body")
                    out.outputStream().use { output -> body.byteStream().copyTo(output) }
                }
                withContext(Dispatchers.Main) {
                    adapter.notifyDataSetChanged()
                    updateStatus("Downloaded: ${episode.title}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("Download failed: ${e.message}") }
            }
        }
    }

    private fun togglePlayed(episode: Episode) {
        val newState = !prefs.isPlayed(episode.id)
        prefs.setPlayed(episode.id, newState)
        if (newState) prefs.setResumeMs(episode.id, 0)
        adapter.notifyDataSetChanged()

        val cfg = prefs.configOrNull() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { SubsonicClient(cfg).scrobble(episode.id, submission = newState) }
        }
    }

    private fun getOrCreatePlayer(): ExoPlayer {
        player?.let { return it }

        val httpFactory = OkHttpDataSource.Factory(OkHttpClient())

        val simpleCache = SimpleCache(
            File(cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(500L * 1024L * 1024L)
        )
        cache = simpleCache

        val cacheFactory = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                updateStatus("Playback error: ${error.errorCodeName}")
                toast("Playback failed: ${error.errorCodeName}")
            }
        })

        binding.playerView.player = exo

        setupMediaNotification(exo)

        player = exo
        return exo
    }

    private fun setupMediaNotification(exo: ExoPlayer) {
        val channelId = "podcast_playback"
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notifManager.getNotificationChannel(channelId) == null) {
            notifManager.createNotificationChannel(
                NotificationChannel(channelId, "Podcast Playback", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val session = MediaSession.Builder(this, exo).build()
        mediaSession = session

        val pm = PlayerNotificationManager.Builder(this, 1001, channelId)
            .setMediaDescriptionAdapter(object : PlayerNotificationManager.MediaDescriptionAdapter {
                override fun getCurrentContentTitle(player: Player): CharSequence {
                    return player.mediaMetadata.title ?: "Podcast"
                }

                override fun createCurrentContentIntent(player: Player) = null

                override fun getCurrentContentText(player: Player): CharSequence {
                    return player.mediaMetadata.artist ?: ""
                }

                override fun getCurrentLargeIcon(
                    player: Player,
                    callback: PlayerNotificationManager.BitmapCallback
                ) = null
            })
            .build()

        pm.setUseFastForwardActionInCompactView(true)
        pm.setUseRewindActionInCompactView(true)
        pm.setPlayer(exo)
        pm.setMediaSessionToken(session.sessionCompatToken)
        notificationManager = pm
    }

    private fun episodeFile(id: String): File {
        val basePath = prefs.downloadFolder.trim()
        val root = if (basePath.isNotBlank()) File(basePath) else File(filesDir, "episodes")
        return File(root, "$id.media")
    }

    private fun saveCurrentProgress() {
        val ep = currentEpisode ?: return
        val p = player ?: return
        prefs.setResumeMs(ep.id, p.currentPosition.coerceAtLeast(0L))
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.isVisible = loading
        binding.refreshButton.isEnabled = !loading
    }

    private fun updateStatus(text: String) {
        binding.statusText.text = text
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onStop() {
        super.onStop()
        saveCurrentProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveCurrentProgress()
        player?.release()
        player = null
        notificationManager?.setPlayer(null)
        notificationManager = null
        mediaSession?.release()
        mediaSession = null
        cache?.release()
        cache = null
    }
}

data class Episode(
    val id: String,
    val title: String,
    val podcastName: String,
    val duration: Int?,
    val publishedAt: String? = null,
    val channelId: String? = null
)

private fun Episode.publishedSortKey(): String = publishedAt ?: ""

data class PodcastShow(
    val title: String,
    val episodes: List<Episode>,
    val channelId: String? = null
)

data class AppConfig(val serverUrl: String, val username: String, val appPassword: String)

data class FeedSearchResult(
    val title: String,
    val author: String?,
    val feedUrl: String
)

private fun elementsByTagNameLocal(root: Element, tag: String): List<Element> {
    val nodes = root.getElementsByTagName("*")
    val out = mutableListOf<Element>()
    for (i in 0 until nodes.length) {
        val el = nodes.item(i) as? Element ?: continue
        val name = (el.localName ?: el.nodeName).substringAfter(':')
        if (name == tag) out += el
    }
    return out
}

class SettingsStore(context: Context) {
    private val sp = context.getSharedPreferences("podcast_settings", Context.MODE_PRIVATE)
    var serverUrl: String
        get() = sp.getString("serverUrl", "") ?: ""
        set(v) = sp.edit().putString("serverUrl", v).apply()
    var username: String
        get() = sp.getString("username", "") ?: ""
        set(v) = sp.edit().putString("username", v).apply()
    var appPassword: String
        get() = sp.getString("appPassword", "") ?: ""
        set(v) = sp.edit().putString("appPassword", v).apply()
    var downloadFolder: String
        get() = sp.getString("downloadFolder", "") ?: ""
        set(v) = sp.edit().putString("downloadFolder", v).apply()

    fun configOrNull(): AppConfig? {
        val url = serverUrl.trim().trimEnd('/')
        val u = username.trim()
        val p = appPassword.trim()
        if (url.isBlank() || u.isBlank() || p.isBlank()) return null
        return AppConfig(url, u, p)
    }

    fun resumeMs(id: String): Long = sp.getLong("resume_$id", 0L)
    fun setResumeMs(id: String, ms: Long) = sp.edit().putLong("resume_$id", ms).apply()
    fun isPlayed(id: String): Boolean = sp.getBoolean("played_$id", false)
    fun setPlayed(id: String, v: Boolean) = sp.edit().putBoolean("played_$id", v).apply()
}

class SubsonicClient(private val cfg: AppConfig) {
    val rawClient = OkHttpClient()
    private val version = "1.16.1"
    private val clientName = "PodcastApp"

    private fun authParams(): Map<String, String> = mapOf(
        "u" to cfg.username,
        "p" to cfg.appPassword,
        "v" to version,
        "c" to clientName,
        "f" to "xml"
    )

    fun getPodcastEpisodes(): List<Episode> {
        val episodes = getFromPodcastChannels()
        if (episodes.isNotEmpty()) return episodes
        return getFromLibraryGenrePodcast()
    }

    private fun getFromPodcastChannels(): List<Episode> {
        val channelsDoc = getXml("/rest/getPodcasts")
        val channels = elementsByTagNameLocal(channelsDoc.documentElement, "channel")
        val episodes = mutableListOf<Episode>()
        for (channel in channels) {
            val podcastName = channel.getAttribute("title").ifBlank { "Podcast" }
            val allEpisodes = elementsByTagNameLocal(channel, "episode")
            for (ep in allEpisodes) {
                val id = ep.getAttribute("id")
                if (id.isBlank()) continue
                episodes += Episode(
                    id = id,
                    title = ep.getAttribute("title").ifBlank { "Episode" },
                    podcastName = podcastName,
                    duration = ep.getAttribute("duration").toIntOrNull(),
                    publishedAt = ep.getAttribute("publishDate")
                        .ifBlank { ep.getAttribute("created") }
                        .ifBlank {
                            ep.getAttribute("year").takeIf { it.isNotBlank() }?.let { "$it-01-01T00:00:00Z" }
                        },
                    channelId = ep.getAttribute("channelId").ifBlank { channel.getAttribute("id").ifBlank { null } }
                )
            }
        }
        return episodes
    }

    private fun getFromLibraryGenrePodcast(): List<Episode> {
        val out = mutableListOf<Episode>()
        val listDoc = getXml("/rest/getAlbumList2", mapOf("type" to "byGenre", "genre" to "Podcast", "size" to "200"))
        val albums = elementsByTagNameLocal(listDoc.documentElement, "album")
        for (album in albums) {
            val albumId = album.getAttribute("id")
            if (albumId.isBlank()) continue
            val albumName = album.getAttribute("name").ifBlank { "Podcast" }
            val albumDoc = getXml("/rest/getAlbum", mapOf("id" to albumId))
            val songs = elementsByTagNameLocal(albumDoc.documentElement, "song")
            for (song in songs) {
                val id = song.getAttribute("id")
                if (id.isBlank()) continue
                out += Episode(
                    id = id,
                    title = song.getAttribute("title").ifBlank { "Episode" },
                    podcastName = albumName,
                    duration = song.getAttribute("duration").toIntOrNull(),
                    publishedAt = song.getAttribute("created")
                        .ifBlank {
                            song.getAttribute("year").takeIf { it.isNotBlank() }?.let { "$it-01-01T00:00:00Z" }
                        }
                )
            }
        }
        return out
    }

    fun buildStreamUrl(id: String): String = buildUrl("/rest/stream", mapOf("id" to id))
    fun buildDownloadUrl(id: String): String = buildUrl("/rest/download", mapOf("id" to id))

    fun scrobble(id: String, submission: Boolean) {
        val req = Request.Builder().url(buildUrl("/rest/scrobble", mapOf("id" to id, "submission" to submission.toString()))).get().build()
        rawClient.newCall(req).execute().close()
    }

    fun createPodcastChannel(feedUrl: String) {
        val req = Request.Builder().url(buildUrl("/rest/createPodcastChannel", mapOf("url" to feedUrl))).get().build()
        rawClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("HTTP ${res.code}")
            val body = res.body?.string().orEmpty()
            if (body.contains("status=\"failed\"", ignoreCase = true)) {
                val msg = Regex("message=\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: "server rejected feed"
                error(msg)
            }
        }
    }

    fun deletePodcastChannel(channelId: String) {
        val req = Request.Builder().url(buildUrl("/rest/deletePodcastChannel", mapOf("id" to channelId))).get().build()
        rawClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("HTTP ${res.code}")
            val body = res.body?.string().orEmpty()
            if (body.contains("status=\"failed\"", ignoreCase = true)) {
                val msg = Regex("message=\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: "server rejected delete"
                error(msg)
            }
        }
    }

    private fun buildUrl(path: String, extra: Map<String, String>): String {
        val b = "${cfg.serverUrl}$path".toHttpUrlOrNull()?.newBuilder() ?: error("Bad URL")
        authParams().forEach { (k, v) -> b.addQueryParameter(k, v) }
        extra.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build().toString()
    }

    private fun getXml(path: String, extra: Map<String, String> = emptyMap()) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(requestStream(buildUrl(path, extra)))

    private fun requestStream(url: String): java.io.InputStream {
        val req = Request.Builder().url(url).get().build()
        val res = rawClient.newCall(req).execute()
        if (!res.isSuccessful) error("HTTP ${res.code}")
        return res.body?.byteStream() ?: error("Empty response")
    }
}

class EpisodesAdapter(
    private val onPlay: (Episode) -> Unit,
    private val onDownload: (Episode) -> Unit,
    private val onTogglePlayed: (Episode) -> Unit,
    private val isDownloaded: (Episode) -> Boolean,
    private val isPlayed: (Episode) -> Boolean
) : RecyclerView.Adapter<EpisodesAdapter.VH>() {
    private val items = mutableListOf<Episode>()

    fun submit(newItems: List<Episode>) {
        items.clear(); items.addAll(newItems); notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.episode_item, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.bind(item, onPlay, onDownload, onTogglePlayed, isDownloaded(item), isPlayed(item))
    }

    override fun getItemCount(): Int = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.titleText)
        private val subtitle = view.findViewById<TextView>(R.id.subtitleText)
        private val releaseDate = view.findViewById<TextView>(R.id.releaseDateText)
        private val play = view.findViewById<Button>(R.id.playBtn)
        private val download = view.findViewById<Button>(R.id.downloadBtn)
        private val played = view.findViewById<Button>(R.id.playedBtn)

        fun bind(item: Episode, onPlay: (Episode) -> Unit, onDownload: (Episode) -> Unit, onTogglePlayed: (Episode) -> Unit, downloaded: Boolean, playedState: Boolean) {
            title.text = item.title
            val duration = item.duration?.let { "${it / 60}m" } ?: ""
            val release = item.publishedAt?.let { formatDateSafe(it) } ?: "Unknown"
            releaseDate.text = "Released: $release"
            subtitle.text = "${if (duration.isNotBlank()) duration else "Length unknown"}${if (downloaded) " • offline" else ""}"
            play.setOnClickListener { onPlay(item) }
            download.text = if (downloaded) "Re-download" else "Download"
            download.setOnClickListener { onDownload(item) }
            played.text = if (playedState) "Unplay" else "Played"
            played.setOnClickListener { onTogglePlayed(item) }
        }

        private fun formatDateSafe(input: String): String {
            return try {
                val dt = OffsetDateTime.parse(input)
                dt.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.US))
            } catch (_: Exception) {
                input.take(10)
            }
        }
    }
}

class ShowsAdapter(private val onOpen: (PodcastShow) -> Unit) : RecyclerView.Adapter<ShowsAdapter.VH>() {
    private val items = mutableListOf<PodcastShow>()
    var onLongPress: ((PodcastShow) -> Unit)? = null

    fun submit(newItems: List<PodcastShow>) {
        items.clear(); items.addAll(newItems); notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.show_item, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], onOpen, onLongPress)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val t1 = view.findViewById<TextView>(R.id.showTitle)
        private val t2 = view.findViewById<TextView>(R.id.showMeta)
        fun bind(item: PodcastShow, onOpen: (PodcastShow) -> Unit, onLongPress: ((PodcastShow) -> Unit)?) {
            t1.text = item.title
            t2.text = "${item.episodes.size} episodes"
            itemView.setOnClickListener { onOpen(item) }
            itemView.setOnLongClickListener {
                onLongPress?.invoke(item)
                true
            }
        }
    }
}
