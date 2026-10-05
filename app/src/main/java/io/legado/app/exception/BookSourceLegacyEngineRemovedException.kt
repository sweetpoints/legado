package io.legado.app.exception

/** A book-source execution path still requires migration to the Dart engine. */
class BookSourceLegacyEngineRemovedException : NoStackTraceException(
    "engine_migration_required: Book-source execution requires the Dart engine",
) {
    val code: String = "engine_migration_required"
}
