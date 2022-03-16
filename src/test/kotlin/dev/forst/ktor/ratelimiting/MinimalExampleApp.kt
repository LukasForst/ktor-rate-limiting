package dev.forst.ktor.ratelimiting


import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.origin
import io.ktor.request.path
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.routing
import java.time.Duration

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
