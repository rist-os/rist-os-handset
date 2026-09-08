package watch.rist.assistant

internal fun maskNumber(n: String): String {
    val d = n.filter { it.isDigit() }
    return if (d.length <= 4) "****" else "…${d.takeLast(4)}"
}
