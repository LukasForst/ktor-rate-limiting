package dev.forst.ktor.ratelimiting

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.util.pipeline.PipelineContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

typealias RateLimitExclusion = suspend PipelineContext<*, ApplicationCall>.() -> Boolean

private val rateLimitingLogger = LoggerFactory.getLogger("dev.forst.ktor.ratelimiting.RateLimiting")

typealias RateLimitKeyExtraction = suspend PipelineContext<*, ApplicationCall>.() -> String?

typealias RateLimitHitAction = suspend PipelineContext<*, ApplicationCall>.(key: String, retryAfter: Long) -> Unit

/**
 * Configuration for the Rate Limiting plugin.
 */
class RateLimitingConfiguration {
    /**
     * See [excludeRequestWhen].
     */
    internal lateinit var requestExclusionFunction: RateLimitExclusion

    /**
     * See [registerLimit].
     */
    internal var rateLimits: MutableMap<UUID, Triple<Long, Duration, RateLimitKeyExtraction>> = mutableMapOf()

    /**
     * See [rateLimitHit].
     */
    internal var rateLimitHitActionFunction: RateLimitHitAction = defaultRateLimitHitAction


    /**
     * Define selector that excludes given route from the rate limiting completely.
     *
     * When the request is excluded no rate limit is executed.
     */
    fun excludeRequestWhen(body: RateLimitExclusion) {
        requestExclusionFunction = body
    }

    /**
     * Register a single limit for the rate limiter.
     *
     * Note, that they share the key map so the keys should be unique across all limits.
     * @param limit - how many requests can be made during a single [window].
     * @param window - window that counts the requests.
     * @param extractKey - what request property to use as the key in the cache.
     */
    fun registerLimit(limit: Long, window: Duration, extractKey: RateLimitKeyExtraction) {
        rateLimits[UUID.randomUUID()] = Triple(limit, window, extractKey)
    }


    /**
     * Action that is executed when the rate limit is hit.
     *
     * Note, that one should execute [PipelineContext.finish] if the pipeline should end and not to proceed further.
     */
    fun rateLimitHit(action: RateLimitHitAction) {
        rateLimitHitActionFunction = action
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
    val limitersSettings = pluginConfig.rateLimits

    val limiter = LinearRateLimiter(
        limitersSettings = limitersSettings.mapValues { (_, rateLimitData) -> rateLimitData.first to rateLimitData.second },
        purgeHitSize = pluginConfig.purgeHitSize,
        purgeHitDuration = pluginConfig.purgeHitDuration
    )
    val extractors = limitersSettings.mapValues { (_, value) -> value.third }
    val rateLimitExclusion = pluginConfig.requestExclusionFunction
    val rateLimitHit = pluginConfig.rateLimitHitActionFunction

    // now install plugin
    application.intercept(ApplicationCallPipeline.Plugins) {
        // determine if it is necessary to filter this request or not
        if (rateLimitExclusion(this)) {
            proceed()
            return@intercept
        }
        // use all extractors to find out if we need to retry
        val limitResult = extractors.firstMappingNotNullOrNull { (extractorId, keyExtraction) ->
            val key = keyExtraction(this)
            if (key != null) {
                limiter.processRequest(extractorId, key)?.let { key to it }
            } else {
                null
            }
        }

        // if no limitResult is defined, proceed in the request pipeline
        if (limitResult == null) {
            proceed()
        } else {
            val (key, retryAfter) = limitResult
            rateLimitHit(key, retryAfter)
        }
    }
}


internal val defaultRateLimitHitAction: RateLimitHitAction = { key, retryAfter ->
    // at this point we want to deny attacker the request,
    // but we also do not want to spend any more resources on processing this request
    // for that reason we don't throw exception, nor return jsons, but rather finish the request here
    call.response.header("Retry-After", retryAfter)
    call.respond(HttpStatusCode.TooManyRequests)
    rateLimitingLogger.warn("Rate limit hit for key \"$key\" - retry after ${retryAfter}s.")
    // finish the request and do not proceed to next step
    finish()
}
