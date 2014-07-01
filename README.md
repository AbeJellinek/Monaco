Monaco
======

Monaco is a parser library with a DSL in the Kotlin language.
It parses [PEG](https://en.wikipedia.org/wiki/Parsing_expression_grammar)-style grammars
with the Packrat constant-time parsing algorithm.

Example
-------

```kotlin
import monaco.compiler.parser.*

public class ParserTest(s: String) : RecursiveDescent(s.asSource()) {
    val pair: Parser<List<Any>> = seq(literal("{")) + zeroOrMore(ref { pair }) + seq(literal("}"))
    val aNotB = oneOrMore(seq(literal("a"), !literal("b"))).flatten()
}

fun main(args: Array<String>) {
    val parser = ParserTest("aaa")
    println(parser.match(parser.aNotB))
    parser.end()
}
```
