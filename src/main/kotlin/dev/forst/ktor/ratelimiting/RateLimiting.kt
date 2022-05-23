package dev.forst.ktor.ratelimiting

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.Hook
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.response.header
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

private val rateLimitingLogger = LoggerFactory.getLogger("dev.forst.ktor.ratelimiting.RateLimiting")

typealias RateLimitExclusion = suspend ApplicationCall.() -> Boolean
typealias RateLimitKeyExtraction = suspend ApplicationCall.() -> String?
typealias RateLimitHitAction = suspend ApplicationCall.(key: String, retryAfter: Long) -> Unit

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

    /**
     * Determines which hook to use to catch the call.
     *
     * By default, it intercepts phase after the user was authenticated.
     */
    var interceptPhase: Hook<suspend (ApplicationCall) -> Unit> = AuthenticationChecked
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
    val phase = pluginConfig.interceptPhase
    // now install plugin
    // we want to guarantee that the auth is ready
    on(phase) { call ->
        // determine if it is necessary to filter this request or not
        if (call.rateLimitExclusion()) {
            return@on
        }
        // use all extractors to find out if we need to retry
        val limitResult = extractors.firstMappingNotNullOrNull { (extractorId, keyExtraction) ->
            val key = call.keyExtraction()
            if (key != null) {
                limiter.processRequest(extractorId, key)?.let { key to it }
            } else {
                null
            }
        }

        // if no limitResult is defined, proceed in the request pipeline
        if (limitResult == null) {
            return@on
        } else {
            val (key, retryAfter) = limitResult
            call.rateLimitHit(key, retryAfter)
        }
    }
}


internal val defaultRateLimitHitAction: RateLimitHitAction = { key, retryAfter ->
    // at this point we want to deny attacker the request,
    // but we also do not want to spend any more resources on processing this request
    // for that reason we don't throw exception, nor return jsons, but rather finish the request here
    response.header("Retry-After", retryAfter)
    respond(HttpStatusCode.TooManyRequests)
    rateLimitingLogger.warn("Rate limit hit for key \"$key\" - retry after ${retryAfter}s.")
}
