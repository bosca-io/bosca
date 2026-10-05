package bosca.ksp.visitors

interface VisitorConsumer<T> {

    fun consume(): List<T>
}