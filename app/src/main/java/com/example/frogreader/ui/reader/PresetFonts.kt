package com.example.frogreader.ui.reader

import com.example.frogreader.R

/**
 * A typeface the app can fetch for the reader on request.
 *
 * The list is deliberately short and all open-source: each row costs a download
 * of several megabytes, so a catalogue of dozens would be a menu nobody reads.
 * Every entry is one static face of the regular weight — the reader's picker is
 * about the body text, and a family's other weights would each add megabytes to
 * a phone for styles the reader never asks the body to use.
 *
 * URLs point at the projects' own GitHub release assets, pinned to a version: a
 * "latest" link would silently change the file behind a name the user already
 * chose. [fileName] is what the file is stored as, and what marks the row as
 * already installed.
 */
internal data class PresetFont(
    val id: String,
    /** The typeface's own name. Proper nouns stay as the foundry writes them. */
    val name: String,
    /** One line about what it looks like, in the reader's language. */
    val summaryRes: Int,
    /** Upstream size, for the "24.7 MB" the user is agreeing to. */
    val sizeBytes: Long,
    val fileName: String,
    val url: String,
    /**
     * Entry to unpack when the release is an archive, as 得意黑's is.
     * Null for a release that serves the font file itself.
     */
    val archiveEntry: String? = null,
)

/**
 * The catalogue.
 *
 * Grouped by Chinese text style — 楷 (brush-like, easiest on long reading), 宋
 * (the printed-book look) and 黑 (clean on screen) — because that is the choice
 * a reader is actually making; which foundry made it is the secondary line.
 * Two sources per style where a good one exists: LXGW's reworked faces and
 * Adobe's originals, plus MiSans for a modern phone-system look and 得意黑 for
 * a display face. All are free for personal and commercial use; they are
 * supplementary to the bundled Literata and IBM Plex Sans.
 */
internal val PresetFonts: List<PresetFont> = listOf(
    PresetFont(
        id = "lxgw-wenkai",
        name = "霞鹜文楷",
        summaryRes = R.string.reader_font_preset_wenkai,
        sizeBytes = 25_928_940,
        fileName = "LXGWWenKai-Regular.ttf",
        url = "https://github.com/lxgw/LxgwWenKai/releases/download/v1.530/LXGWWenKai-Regular.ttf",
    ),
    PresetFont(
        id = "lxgw-neozhisong",
        name = "霞鹜新致宋",
        summaryRes = R.string.reader_font_preset_zhisong,
        sizeBytes = 10_483_620,
        fileName = "LXGWNeoZhiSong.ttf",
        url = "https://github.com/lxgw/LxgwNeoZhiSong/releases/download/v1.071/LXGWNeoZhiSong.ttf",
    ),
    PresetFont(
        id = "source-han-serif",
        name = "思源宋体",
        summaryRes = R.string.reader_font_preset_source_serif,
        sizeBytes = 11_626_108,
        fileName = "SourceHanSerifCN-Regular.otf",
        // jsDelivr rather than raw.githubusercontent: the same Adobe file, from
        // the CDN that is actually reachable from mainland China. Pinned to a
        // commit so "release" moving does not silently change the face.
        url = "https://cdn.jsdelivr.net/gh/adobe-fonts/source-han-serif@" +
            "7889f11bf31170b5d092a083b357c8c8130f89e0/SubsetOTF/CN/SourceHanSerifCN-Regular.otf",
    ),
    PresetFont(
        id = "lxgw-neoxihei",
        name = "霞鹜新晰黑",
        summaryRes = R.string.reader_font_preset_xihei,
        sizeBytes = 7_475_784,
        fileName = "LXGWNeoXiHei.ttf",
        url = "https://github.com/lxgw/LxgwNeoXiHei/releases/download/v1.309/LXGWNeoXiHei.ttf",
    ),
    PresetFont(
        id = "source-han-sans",
        name = "思源黑体",
        summaryRes = R.string.reader_font_preset_source_sans,
        sizeBytes = 8_429_224,
        fileName = "SourceHanSansCN-Regular.otf",
        url = "https://cdn.jsdelivr.net/gh/adobe-fonts/source-han-sans@" +
            "a4f7cf94edfb9d7ffbdfc4841de276358bd7e0f2/SubsetOTF/CN/SourceHanSansCN-Regular.otf",
    ),
    PresetFont(
        id = "misans",
        name = "MiSans",
        summaryRes = R.string.reader_font_preset_misans,
        sizeBytes = 7_924_400,
        fileName = "MiSans-Regular.ttf",
        // Xiaomi's own CDN, downloaded from the MiSans page.
        url = "https://cdn.cnbj1.fds.api.mi-img.com/vipmlmodel/font/MiSans/MiSans-Regular.ttf",
    ),
    PresetFont(
        id = "smiley-sans",
        name = "得意黑",
        summaryRes = R.string.reader_font_preset_smiley,
        // The archive, not the face inside it; see [archiveEntry].
        sizeBytes = 5_781_344,
        fileName = "SmileySans-Oblique.ttf",
        url = "https://github.com/atelier-anchor/smiley-sans/releases/download/v2.0.1/smiley-sans-v2.0.1.zip",
        archiveEntry = "SmileySans-Oblique.ttf",
    ),
)