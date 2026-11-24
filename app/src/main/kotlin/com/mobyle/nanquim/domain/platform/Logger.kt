package com.mobyle.nanquim.domain.platform

/** Where the domain's log lines go. Each platform installs its own; until then they are dropped, as in unit tests. */
interface LogSink {
    fun warn(
        tag: String,
        message: String,
        error: Throwable? = null,
    )

    fun error(
        tag: String,
        message: String,
        error: Throwable? = null,
    )
}

/** The domain's logger: the one global, so plain classes can log without every constructor taking a sink. */
object Logger {
    @Volatile
    var sink: LogSink? = null

    fun w(
        tag: String,
        message: String,
        error: Throwable? = null,
    ) {
        sink?.warn(tag, message, error)
    }

    fun e(
        tag: String,
        message: String,
        error: Throwable? = null,
    ) {
        sink?.error(tag, message, error)
    }
}
