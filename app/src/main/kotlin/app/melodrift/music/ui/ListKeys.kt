package app.melodrift.music.ui

import app.melodrift.music.net.Song

/**
 * 为歌曲列表生成「稳定且唯一」的 LazyColumn key。
 *
 * 直接用 `song.id` 不安全：播放队列与歌单里同一首歌可能出现两次，
 * 而 LazyList 遇到重复 key 会抛 IllegalArgumentException 直接崩在滚动时。
 * 这里给同 id 的第 n 次出现追加序号（`"id#n"`）——
 * key 绑定条目本身而非位置，所以拖拽排序、增删条目时状态不会错位。
 */
internal fun songKeys(songs: List<Song>): List<String> {
    val seen = HashMap<Long, Int>(songs.size)
    return buildList(songs.size) {
        for (s in songs) {
            val n = (seen[s.id] ?: 0) + 1
            seen[s.id] = n
            add("${s.id}#$n")
        }
    }
}
