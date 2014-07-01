package monaco.compiler.parser

import java.util.regex.Pattern
import java.util.ArrayList
import java.util.Stack

public abstract class RecursiveDescent(val source: Source, val filename: String = "<input>") {
    public fun match<T>(parser: Parser<T>): Result<T> = parser.match(source)

    public fun end() {
        if (source.hasMore())
            throw ParseException(error(source, "expected end of source, but found more").error)
    }

    public fun ref<T>(f: () -> Parser<T>): Parser<T> {
        class Ref(val ff: () -> Parser<T>) : Parser<T>() {
            override var ignored: Boolean = false
                get() = ff().ignored

            override fun doMatch(source: Source): Result<T> {
                return ff().doMatch(source)
            }

            override fun toString(): String {
                return "..."
            }
        }

        return Ref(f)
    }

    public fun zeroOrMore<T>(p: Parser<T>): Parser<List<T>> {
        return object : Parser<List<T>>() {
            override fun doMatch(source: Source): Result<List<T>> {
                val list = ArrayList<T>()
                val pos = source.position
                while (source.hasMore()) {
                    val match = p.match(source)
                    when (match) {
                        is Success ->
                            list.add(match.value)
                        is Failure ->
                            break
                    }
                }
                return Success(list, pos, source.position)
            }

            override fun toString(): String {
                return "zeroOrMore($p)"
            }
        }
    }

    public fun oneOrMore<T>(p: Parser<T>): Parser<List<T>> {
        return object : Parser<List<T>>() {
            override fun doMatch(source: Source): Result<List<T>> {
                val list = ArrayList<T>()
                val pos = source.position
                while (source.hasMore()) {
                    val match = p.match(source)
                    when (match) {
                        is Success -> list.add(match.value)
                        is Failure -> {
                            println("result: $match")
                            break
                        }
                    }
                }
                if (list.empty)
                    return error(source, "found no matches, but one or more are required")
                return Success(list, pos, source.position)
            }

            override fun toString(): String {
                return "oneOrMore($p)"
            }
        }
    }
}

public abstract class Source {
    var position: Int = 0
    var line: Int = 1
    var col: Int = 0
    var name: String = "<input>"

    private var posStack = Stack<Triple<Int, Int, Int>>()

    public fun lines(): Array<String> = asSequence().toString().split("(\\r\\n)|[\\r\\n]")

    public abstract fun asSequence(): CharSequence

    public fun consume(chars: Int) {
        for (c in asSequence().subSequence(0, chars).toString()) {
            if (c == '\r' || c == '\n') {
                line++
                col = 0
            } else {
                col++
            }
        }

        position += chars
    }

    public fun mark() {
        posStack.push(Triple(position, line, col))
    }

    public fun reset() {
        if (posStack.empty)
            throw RuntimeException("mark not set")
        val (lastPosition, lastLine, lastCol) = posStack.pop()!!
        position = lastPosition
        line = lastLine
        col = lastCol
    }

    public fun popPosition(): Triple<Int, Int, Int> {
        if (posStack.empty)
            throw RuntimeException("mark not set")
        return posStack.pop()!!
    }

    public abstract fun hasMore(): Boolean

    public abstract fun copy(): Source
}

public trait Result<out T> {
    public val startPosition: Int
    public val endPosition: Int

    public fun orElse(t: () -> Result<T>): Result<T>

    public fun map<U>(f: (T) -> U): Result<U>
}

public data class Success<out T>(public val value: T,
                                 public override val startPosition: Int,
                                 public override val endPosition: Int) : Result<T> {
    public override fun orElse(t: () -> Result<T>): Result<T> {
        return this
    }

    override fun <U> map(f: (T) -> U): Result<U> {
        return Success(f(value), startPosition, endPosition)
    }
}

public data class Failure(public val error: String,
                          public override val startPosition: Int) : Result<Nothing> {
    override val endPosition = startPosition

    public override fun orElse(t: () -> Result<Nothing>): Result<Nothing> {
        return t()
    }

    override fun <U> map(f: (Nothing) -> U): Result<U> {
        return this
    }
}

public class ParseException(msg: String) : RuntimeException(msg)

public fun error(source: Source, msg: String): Failure {
    val sb = StringBuilder()
    sb.append("Error: $msg\n")
    sb.append("  ")
    sb.append(source.lines()[source.line - 1])
    sb.append("\n  ")
    for (i in 0..source.col - 1)
        sb.append(' ')
    sb.append("^\n")
    sb.append("at line ${source.line}, column ${source.col} in ${source.name}")
    return Failure(sb.toString(), source.position)
}

public abstract class Parser<out T> {
    private val cache = hashMapOf<Source, MutableMap<Int, Result<T>>>()
    open var ignored = false

    public fun match(source: Source): Result<T> {
        val map = cache.getOrPut(source, { hashMapOf() })
        if (map.containsKey(source.position)) {
            val result = map[source.position]!!
            source.position = result.endPosition
            return result
        } else {
            source.mark()
            val match = doMatch(source)
            map[match.startPosition] = match
            if (match is Failure)
                source.reset()
            else
                source.popPosition()
            return match
        }
    }

    public abstract fun doMatch(source: Source): Result<T>

    public fun ignore(): Parser<T> {
        ignored = true
        return this
    }
}

public fun <T> Parser<T>.or(p2: Parser<T>): Parser<T> {
    val p1 = this
    return object : Parser<T>() {
        override fun doMatch(source: Source): Result<T> {
            return p1.match(source).orElse { p2.match(source) }
        }

        override fun toString(): String {
            return "$p1 or $p2"
        }
    }
}

public fun <T, R> Parser<T>.map(f: (T) -> R): Parser<R> {
    val p = this
    return object : Parser<R>() {
        override fun doMatch(source: Source): Result<R> {
            return p.match(source).map(f)
        }

        override fun toString(): String {
            return "($p).map(f)"
        }
    }
}

public fun seq<T>(vararg ps: Parser<T>): Parser<List<T>> {
    return object : Parser<List<T>>() {
        override fun doMatch(source: Source): Result<List<T>> {
            val list = ArrayList<T>()
            val pos = source.position
            for (p in ps) {
                val result = p.match(source)

                when (result) {
                    is Success -> if (!p.ignored) list.add(result.value)
                    is Failure -> return Failure("not all elements were matched", pos)
                }
            }
            return Success(list, pos, source.position)
        }

        override fun toString(): String {
            if (ps.size == 2)
                return "${ps[0]} + ${ps[1]}"
            else
                return "${ps.makeString(", ", prefix = "seq(", postfix = ")")}"
        }
    }
}

public fun <T> Parser<List<T>>.plus(that: Parser<List<T>>): Parser<List<T>> {
    val self = this

    return object : Parser<List<T>>() {
        override fun doMatch(source: Source): Result<List<T>> {
            val list = ArrayList<T>()
            val pos = source.position

            val selfMatch = self.match(source)
            when (selfMatch) {
                is Success -> list.addAll(selfMatch.value)
                is Failure -> return Failure("not all elements were matched", pos)
            }

            val thatMatch = that.match(source)
            when (thatMatch) {
                is Success -> list.addAll(thatMatch.value)
                is Failure -> return Failure("not all elements were matched", pos)
            }

            return Success(list, pos, source.position)
        }
    }
}

[suppress("BASE_WITH_NULLABLE_UPPER_BOUND")]
public fun <T> Parser<T?>.orNull(): Parser<T?> {
    val p = this
    return object : Parser<T?>() {
        override fun doMatch(source: Source): Result<T?> {
            return p.match(source).orElse { Success(null, source.position, source.position) }
        }

        override fun toString(): String {
            return "$p.orNull()"
        }
    }
}

public fun <T> Parser<T>.plus(): Parser<Boolean> {
    val p = this
    return object : Parser<Boolean>() {
        override fun doMatch(source: Source): Result<Boolean> {
            val pos = source.position
            source.mark()
            val result = when (p.match(source)) {
                is Success -> Success(true, pos, pos)
                else -> error(source, "expected match")
            }
            source.reset()

            return result
        }

        override fun toString(): String {
            return "+($p)"
        }
    }.ignore()
}

public fun <T> Parser<T>.not(): Parser<Boolean> {
    val p = this
    return object : Parser<Boolean>() {
        override fun doMatch(source: Source): Result<Boolean> {
            val pos = source.position
            source.mark()
            val result = when (p.match(source)) {
                is Success -> error(source, "expected non-match")
                else -> Success(true, pos, pos)
            }
            source.reset()

            return result
        }

        override fun toString(): String {
            return "!($p)"
        }
    }.ignore()
}

public fun <T> Parser<Collection<Collection<T>>>.flatten(): Parser<Collection<T>> {
    val p = this
    return object : Parser<Collection<T>>() {
        override fun doMatch(source: Source): Result<Collection<T>> {
            return p.match(source).map { it.flatMap { it } }
        }

        override fun toString(): String {
            return "$p.flatten()"
        }
    }.ignore()
}

public class RegexParser(val regex: Pattern) : Parser<String>() {
    override fun doMatch(source: Source): Result<String> {
        val matcher = regex.matcher(source.asSequence())
        if (!matcher.lookingAt())
            return error(source, "unable to find match")
        val pos = source.position
        source.consume(matcher.end())
        return Success(matcher.group(), pos, source.position)
    }

    override fun toString(): String {
        return "regex(\"$regex\")"
    }
}

public class LiteralParser(val value: String) : Parser<String>() {
    override fun doMatch(source: Source): Result<String> {
        if (!source.asSequence().toString().startsWith(value))
            return error(source, "unable to find match")
        val pos = source.position
        source.consume(value.length)
        return Success(value, pos, source.position)
    }

    override fun toString(): String {
        return "literal(\"$value\")"
    }
}

class CharSource(val original: CharSequence) : Source() {
    override fun asSequence(): CharSequence {
        return original.subSequence(position, original.length) ?: ""
    }

    override fun hasMore(): Boolean {
        return position < original.length
    }

    override fun copy(): Source {
        return CharSource(original)
    }
}

public fun CharSequence.asSource(): Source {
    return CharSource(this)
}

public fun Pattern.plus(): Parser<String> {
    return RegexParser(this)
}

public fun regex(s: String): Parser<String> {
    return RegexParser(Pattern.compile(s))
}

public fun literal(s: String): Parser<String> {
    return LiteralParser(s)
}

public fun fail(msg: String): Parser<Nothing> {
    return object : Parser<Nothing>() {
        override fun doMatch(source: Source): Result<Nothing> {
            return error(source, msg)
        }

        override fun toString(): String {
            return "fail(\"$msg\")"
        }
    }
}

public fun succeed<T>(value: T): Parser<T> {
    return object : Parser<T>() {
        override fun doMatch(source: Source): Result<T> {
            return Success(value, source.position, source.position)
        }

        override fun toString(): String {
            return "succeed($value)"
        }
    }
}
