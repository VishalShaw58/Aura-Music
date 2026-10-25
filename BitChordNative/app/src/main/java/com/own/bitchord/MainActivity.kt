package com.own.bitchord

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Song(val id: Long, val title: String, val artist: String, val album: String, val dur: Long) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    fun item(): MediaItem = MediaItem.Builder().setMediaId(id.toString()).setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).build()).build()
}

fun loadSongs(c: Context): List<Song> {
    val out = mutableListOf<Song>()
    val m = MediaStore.Audio.Media
    val proj = arrayOf(m._ID, m.TITLE, m.ARTIST, m.ALBUM, m.DURATION)
    c.contentResolver.query(m.EXTERNAL_CONTENT_URI, proj, "${m.IS_MUSIC}!=0", null, "${m.TITLE} COLLATE NOCASE")?.use { q ->
        while (q.moveToNext()) out += Song(q.getLong(0), q.getString(1) ?: "Unknown", q.getString(2) ?: "Unknown Artist", q.getString(3) ?: "Unknown Album", q.getLong(4))
    }
    return out
}

suspend fun thumb(c: Context, s: Song?): Bitmap? = withContext(Dispatchers.IO) {
    if (s == null || Build.VERSION.SDK_INT < 29) null
    else runCatching { c.contentResolver.loadThumbnail(s.uri, Size(512, 512), null) }.getOrNull()
}

fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val red = Color(0xFFFA2D48)
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme(primary = red) else lightColorScheme(primary = red)) {
                Surface(Modifier.fillMaxSize()) { Main() }
            }
        }
    }
}

@Composable
fun Art(s: Song?, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, s?.id) { value = thumb(ctx, s) }
    Box(modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, Color(0xFF6A5CFF))))) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
fun SongList(list: List<Song>, curId: String, favs: Set<String>, onPlay: (Int) -> Unit, onFav: (Song) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
            Row(Modifier.fillMaxWidth().clickable { onPlay(i) }.padding(12.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(s, 48.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                        color = if (s.id.toString() == curId) MaterialTheme.colorScheme.primary else Color.Unspecified)
                    Text("${s.artist} · ${fmt(s.dur)}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (s.id.toString() in favs) "♥" else "♡", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { onFav(s) }.padding(8.dp))
            }
        }
    }
}

@Composable
fun Main() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("p", 0) }
    var songs by remember { mutableStateOf(listOf<Song>()) }
    var favs by remember { mutableStateOf(prefs.getStringSet("fav", emptySet())!!.toSet()) }
    var ctl by remember { mutableStateOf<MediaController?>(null) }
    var curId by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(0) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var tab by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var full by remember { mutableStateOf(false) }
    var showQ by remember { mutableStateOf(false) }
    var sleepJob by remember { mutableStateOf<Job?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var skipSil by remember { mutableStateOf(false) }

    val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch { songs = withContext(Dispatchers.IO) { runCatching { loadSongs(ctx) }.getOrDefault(emptyList()) } }
    }
    LaunchedEffect(Unit) { launcher.launch(perms) }

    DisposableEffect(Unit) {
        val f = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))).buildAsync()
        val l = object : Player.Listener {
            override fun onEvents(p: Player, e: Player.Events) {
                curId = p.currentMediaItem?.mediaId ?: ""; playing = p.isPlaying; shuffle = p.shuffleModeEnabled; repeat = p.repeatMode
            }
        }
        f.addListener({ val c = f.get(); ctl = c; c.addListener(l); curId = c.currentMediaItem?.mediaId ?: "" }, ContextCompat.getMainExecutor(ctx))
        onDispose { MediaController.releaseFuture(f) }
    }
    LaunchedEffect(ctl) { while (isActive) { ctl?.let { pos = it.currentPosition; dur = maxOf(0L, it.duration) }; delay(400) } }

    val cur = songs.firstOrNull { it.id.toString() == curId }
    val play: (List<Song>, Int) -> Unit = { list, i ->
        ctl?.let { c -> c.setMediaItems(list.map { it.item() }, i, 0L); c.prepare(); c.play() }
    }
    val toggleFav: (Song) -> Unit = { s ->
        val k = s.id.toString(); favs = if (k in favs) favs - k else favs + k
        prefs.edit().putStringSet("fav", favs).apply()
    }
    BackHandler(full || detail != null) { if (full) full = false else detail = null }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(Modifier.weight(1f)) {
                val d = detail
                when {
                    tab == 4 -> Settings(ctl, speed, { speed = it; ctl?.setPlaybackSpeed(it) }, skipSil, { skipSil = it; Engine.player?.skipSilenceEnabled = it },
                        { m -> sleepJob?.cancel(); if (m > 0) sleepJob = scope.launch { delay(m * 60_000L); ctl?.pause() } })
                    d != null -> Column {
                        Text("← ${d.second}", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { detail = null }.padding(16.dp))
                        val l = songs.filter { if (d.first == 1) it.album == d.second else it.artist == d.second }
                        SongList(l, curId, favs, { play(l, it) }, toggleFav)
                    }
                    tab == 0 -> SongList(songs, curId, favs, { play(songs, it) }, toggleFav)
                    tab == 3 -> { val l = songs.filter { it.id.toString() in favs }; SongList(l, curId, favs, { play(l, it) }, toggleFav) }
                    else -> {
                        val kind = tab
                        val groups = songs.groupBy { if (kind == 1) it.album else it.artist }.toList().sortedBy { it.first.lowercase() }
                        LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(groups) { _, g ->
                                Row(Modifier.fillMaxWidth().clickable { detail = kind to g.first }.padding(12.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Art(g.second.first(), 56.dp)
                                    Column(Modifier.padding(start = 12.dp)) { Text(g.first, fontWeight = FontWeight.SemiBold, maxLines = 1); Text("${g.second.size} songs", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                    }
                }
                if (songs.isEmpty() && tab != 4) Text("No music found. Grant permission and put songs on the device.", Modifier.align(Alignment.Center).padding(24.dp))
            }
            if (cur != null) Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .85f)).clickable { full = true }) {
                LinearProgressIndicator(progress = { pos.toFloat() / dur.coerceAtLeast(1) }, Modifier.fillMaxWidth())
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Art(cur, 44.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(cur.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold); Text(cur.artist, maxLines = 1, fontSize = 12.sp) }
                    Text(if (playing) "⏸" else "▶", fontSize = 26.sp, modifier = Modifier.clickable { ctl?.let { if (it.isPlaying) it.pause() else it.play() } }.padding(10.dp))
                    Text("⏭", fontSize = 26.sp, modifier = Modifier.clickable { ctl?.seekToNext() }.padding(10.dp))
                }
            }
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .9f)) {
                listOf("Songs", "Albums", "Artists", "Favs", "Settings").forEachIndexed { i, n ->
                    NavigationBarItem(tab == i, { tab = i; detail = null }, icon = { Text(listOf("♪", "◉", "☺", "♥", "⚙")[i], fontSize = 20.sp) }, label = { Text(n, fontSize = 11.sp) })
                }
            }
        }
        if (full && cur != null) FullPlayer(cur, ctl, playing, shuffle, repeat, pos, dur, showQ, { showQ = !showQ }, { full = false }, favs, toggleFav)
    }
}

@Composable
fun FullPlayer(s: Song, c: MediaController?, playing: Boolean, shuffle: Boolean, repeat: Int, pos: Long, dur: Long,
               showQ: Boolean, onQ: () -> Unit, onClose: () -> Unit, favs: Set<String>, onFav: (Song) -> Unit) {
    val ctx = LocalContext.current
    val base = MaterialTheme.colorScheme.primary
    val accent by produceState(base, s.id) {
        value = thumb(ctx, s)?.let { b -> Color(Palette.from(b).generate().getDarkMutedColor(base.hashCode())) } ?: base
    }
    val bg = MaterialTheme.colorScheme.background
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(accent, bg))).statusBarsPadding().navigationBarsPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("⌄", fontSize = 30.sp, modifier = Modifier.clickable { onClose() })
        if (showQ && c != null) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(List(c.mediaItemCount) { c.getMediaItemAt(it) }) { i, m ->
                    Text(m.mediaMetadata.title?.toString() ?: "", Modifier.fillMaxWidth().clickable { c.seekTo(i, 0L) }.padding(10.dp),
                        color = if (m.mediaId == s.id.toString()) base else Color.Unspecified, maxLines = 1)
                }
            }
        } else { Spacer(Modifier.weight(1f)); Art(s, 300.dp); Spacer(Modifier.weight(1f)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.artist, fontSize = 16.sp, maxLines = 1)
                Engine.player?.audioFormat?.let { Text("${it.sampleMimeType?.substringAfter('/')} · ${it.sampleRate} Hz · ${it.channelCount}ch", fontSize = 11.sp) }
            }
            Text(if (s.id.toString() in favs) "♥" else "♡", fontSize = 28.sp, modifier = Modifier.clickable { onFav(s) }.padding(8.dp))
        }
        Slider(pos.toFloat(), { c?.seekTo(it.toLong()) }, valueRange = 0f..maxOf(1f, dur.toFloat()))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(fmt(pos), fontSize = 12.sp); Text(fmt(dur), fontSize = 12.sp) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("🔀", fontSize = 24.sp, modifier = Modifier.clickable { c?.shuffleModeEnabled = !shuffle }.background(if (shuffle) base.copy(.3f) else Color.Transparent).padding(8.dp))
            Text("⏮", fontSize = 34.sp, modifier = Modifier.clickable { c?.seekToPrevious() })
            Text(if (playing) "⏸" else "▶", fontSize = 52.sp, modifier = Modifier.clickable { c?.let { if (it.isPlaying) it.pause() else it.play() } })
            Text("⏭", fontSize = 34.sp, modifier = Modifier.clickable { c?.seekToNext() })
            Text(if (repeat == 1) "🔂" else "🔁", fontSize = 24.sp, modifier = Modifier.clickable { c?.repeatMode = (repeat + 2) % 3 }.background(if (repeat != 0) base.copy(.3f) else Color.Transparent).padding(8.dp))
        }
        Text(if (showQ) "Hide queue" else "Queue", Modifier.clickable { onQ() }.padding(8.dp), color = base)
    }
}

@Composable
fun Settings(c: MediaController?, speed: Float, onSpeed: (Float) -> Unit, skip: Boolean, onSkip: (Boolean) -> Unit, onSleep: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Playback speed ${"%.2f".format(speed)}×", Modifier.padding(top = 16.dp))
        Slider(speed, onSpeed, valueRange = 0.5f..2f)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Skip silence", Modifier.weight(1f)); Switch(skip, onSkip) }
        Text("Sleep timer", Modifier.padding(top = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0, 15, 30, 60).forEach { m -> Button({ onSleep(m) }) { Text(if (m == 0) "Off" else "${m}m") } } }
        Text("Equalizer", Modifier.padding(top = 20.dp), fontWeight = FontWeight.SemiBold)
        val eq = Engine.eq
        if (eq == null) Text("Start playing a song first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            val r = eq.bandLevelRange
            for (b in 0 until eq.numberOfBands) {
                var lv by remember { mutableFloatStateOf(eq.getBandLevel(b.toShort()).toFloat()) }
                Text("${eq.getCenterFreq(b.toShort()) / 1000} Hz", fontSize = 12.sp)
                Slider(lv, { lv = it; eq.setBandLevel(b.toShort(), it.toInt().toShort()) }, valueRange = r[0].toFloat()..r[1].toFloat())
            }
        }
    }
}
