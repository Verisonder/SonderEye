plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

// The Android app's code that has no Android in it: the data sources, the maths, the parsers.
sourceSets {
    main {
        kotlin {
            srcDir("../../app/src/main/java")
            include(
                "com/verisonder/sondereye/core/**",
                "com/verisonder/sondereye/data/Net.kt",
                "com/verisonder/sondereye/data/Feeds.kt",
            )
        }
    }
    test {
        kotlin {
            srcDir("../../app/src/test/java")
            include("com/verisonder/sondereye/core/**")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
