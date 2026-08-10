// Use an integer for version numbers
version = 5

cloudstream {
    description = "欧乐影院 — Chinese movies, series, variety and anime"
    authors = listOf("recloudstream")

    // 0 = Down, 1 = Ok, 2 = Slow, 3 = Beta
    status = 1

    tvTypes = listOf("Movie", "TvSeries", "Anime", "Others")
    iconUrl = "https://www.google.com/s2/favicons?domain=www.olehdtv.com&sz=%size%"

    isCrossPlatform = false
}

dependencies {
    // Stub JAR containing Plugin.class (app-side class with openSettings).
    // Not shipped in the .cs3 — the host Cloudstream APK provides it at runtime.
    compileOnly(fileTree("libs") { include("*.jar") })
}
