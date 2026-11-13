package de.aikitori.retagger.core

/** Sorts "2 - …" before "10 - …". */
object NaturalOrder : Comparator<String> {
    private val chunks = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val left = chunks.findAll(a.lowercase()).map { it.value }.toList()
        val right = chunks.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]
            val y = right[i]
            val result = if (x[0].isDigit() && y[0].isDigit()) {
                x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.compareTo(y)
            } else {
                x.compareTo(y)
            }
            if (result != 0) return result
        }
        return left.size.compareTo(right.size)
    }

    fun <T> by(selector: (T) -> String): Comparator<T> = Comparator { a, b -> compare(selector(a), selector(b)) }
}
