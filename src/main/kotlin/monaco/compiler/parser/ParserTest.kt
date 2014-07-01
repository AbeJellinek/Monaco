package monaco.compiler.parser

public class ParserTest(s: String) : RecursiveDescent(s.asSource()) {
    val pair: Parser<List<Any>> = seq(literal("{")) + zeroOrMore(ref { pair }) + seq(literal("}"))
    val aNotB = oneOrMore(seq(literal("a"), !literal("b"))).flatten()
}

fun main(args: Array<String>) {
    try {
        val parser = ParserTest("aaa")
        println(parser.match(parser.aNotB))
        parser.end()
    } catch (e: ParseException) {
        System.err.println(e.getMessage())
    }
}
