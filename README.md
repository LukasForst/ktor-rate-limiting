# Ktor Rate Limiting

A simple library that enables Rate Limiting in Ktor.

## Installation

Include following in your `build.gradle.kts`:

```kotlin
implementation("dev.forst", "ktor-rate-limiting", "1.1.0")
```

## Usage

This is minimal implementation of the Ktor app that uses Rate Limiting:

```kotlin
/**
 * Minimal Ktor application with Rate Limiting enabled.
 */
fun Application.minimalExample() {
    // install feature
    install(RateLimiting) {
        // allow 10 requests
        limit = 10
        // each 1 minute
        window = Duration.ofMinutes(1)
        // use host as a key to determine who is who
        extractKey { call.request.origin.host }
        // and exclude path which ends with "excluded"
        excludeRequestWhen {
            it.path().endsWith("excluded")
        }
    }
    // now add some routes
    routing {
        get {
            call.respondText("Hello ${call.request.origin.host}")
        }

        get("excluded") {
            call.respondText("Hello ${call.request.origin.host}")
        }
    }
}
```

For details see [MinimalExampleApp.kt](src/test/kotlin/dev/forst/ktor/ratelimiting/MinimalExampleApp.kt) with this example application
and [TestMinimalExampleApp.kt](src/test/kotlin/dev/forst/ktor/ratelimiting/TestMinimalExampleApp.kt) which verifies that this app works as  
expected.
