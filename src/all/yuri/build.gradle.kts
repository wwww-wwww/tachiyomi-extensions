import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Yuri"
    versionCode = 31
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"

    source {
        lang = "all"
        baseUrl = "https://yuri.grass.moe"
        id = 323910630341645598
    }

    deeplink {
        host("yuri.grass.moe")
        path("/series/..*")
        path("/multi/..*")
    }
}
