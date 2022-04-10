package dev.forst.ktor.ratelimiting

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.util.pipeline.PipelineContext
import org.slf4j.LoggerFactory
import java.time.Duration

typealias RateLimitExclusion = (request: ApplicationRequest) -> Boolean
typealias RateLimitKeyExtraction = PipelineContext<*, ApplicationCall>.() -> String

private val rateLimitingLogger = LoggerFactory.getLogger("dev.forst.ktor.ratelimiting.RateLimiting")

/**
 * Configuration for the Rate Limiting plugin.
 */
class RateLimitingConfiguration {
    /**
     * See [LinearRateLimiter.limit].
     */
    var limit: Long = 0

    /**
     * See [LinearRateLimiter.window].
     */
    lateinit var window: Duration

    /**
     * See [extractKey].
     */
    internal lateinit var keyExtractionFunction: RateLimitKeyExtraction

    /**
     * What request property to use as the key in the cache - or in other words, how
     * to identify a single user.
     */
    fun extractKey(body: RateLimitKeyExtraction) {
        keyExtractionFunction = body
    }

    /**
     * See [excludeRequestWhen].
     */
    internal lateinit var requestExclusionFunction: RateLimitExclusion

    /**
     * Define selector that excludes given route from the rate limiting.
     */
    fun excludeRequestWhen(body: RateLimitExclusion) {
        requestExclusionFunction = body
    }

    /**
     * See [LinearRateLimiter.purgeHitSize].
     */
    var purgeHitSize: Int = DEFAULT_PURGE_HIT_SIZE

    /**
     * See [LinearRateLimiter.purgeHitDuration].
     */
    var purgeHitDuration: Duration = DEFAULT_PURGE_HIT_DURATION
}

/**
 * Simple rate limiting implementation using [LinearRateLimiter].
 */
val RateLimiting = createApplicationPlugin(
    name = "RateLimiting",
    createConfiguration = ::RateLimitingConfiguration
) {
    val limiter = LinearRateLimiter(
        limit = pluginConfig.limit,
        window = pluginConfig.window,
        purgeHitSize = pluginConfig.purgeHitSize,
        purgeHitDuration = pluginConfig.purgeHitDuration
    )
    val keyExtraction = pluginConfig.keyExtractionFunction
    val rateLimitExclusion = pluginConfig.requestExclusionFunction

    // now install plugin
    application.intercept(ApplicationCallPipeline.Plugins) {
        // determine if it is necessary to filter this request or not
        if (rateLimitExclusion(call.request)) {
            proceed()
            return@intercept
        }
        // determine remote host / key in the limiting implementation
        val remoteHost = keyExtraction(this)
        val retryAfter = limiter.processRequest(remoteHost)
        // if no retryAfter is defined, proceed in the request pipeline
        if (retryAfter == null) {
            proceed()
        } else {
            // at this point we want to deny attacker the request,
            // but we also do not want to spend any more resources on processing this request
            // for that reason we don't throw exception, nor return jsons, but rather finish the request here
            call.response.header("Retry-After", retryAfter)
            call.respond(HttpStatusCode.TooManyRequests)
            rateLimitingLogger.warn("Rate limit hit for host $remoteHost - retry after ${retryAfter}s.")
            // finish the request and do not proceed to next step
            finish()
        }
    }
}
