package app.hondadash

/**
 * Evaluates Torque-style PID equations, for example "(Signed(A)*256+B)/10" or "A*256+B".
 * Variables A..H are the response data bytes (0..255). Signed(x) turns a byte into -128..127.
 * {A:3} is bit 3 of byte A (0 or 1). Supports + - * / and parentheses.
 */
class Expr(private val src: String) {
    private sealed class Node {
        class Num(val v: Double) : Node()
        class Var(val i: Int) : Node()
        class Bit(val i: Int, val bit: Int) : Node()
        class Neg(val a: Node) : Node()
        class Bin(val op: Char, val a: Node, val b: Node) : Node()
        class Call(val name: String, val a: Node) : Node()
    }

    private var pos = 0
    private val root: Node

    init {
        root = parseExpr()
        skip()
        if (pos != src.length) throw IllegalArgumentException("Unexpected '${src[pos]}' in $src")
    }

    fun eval(bytes: IntArray): Double = ev(root, bytes)

    private fun ev(n: Node, b: IntArray): Double = when (n) {
        is Node.Num -> n.v
        is Node.Var -> if (n.i < b.size) b[n.i].toDouble() else 0.0
        is Node.Bit -> if (n.i < b.size) ((b[n.i] shr n.bit) and 1).toDouble() else 0.0
        is Node.Neg -> -ev(n.a, b)
        is Node.Bin -> {
            val x = ev(n.a, b)
            val y = ev(n.b, b)
            when (n.op) {
                '+' -> x + y
                '-' -> x - y
                '*' -> x * y
                else -> if (y == 0.0) 0.0 else x / y
            }
        }
        is Node.Call -> {
            val x = ev(n.a, b)
            when (n.name.lowercase()) {
                "signed" -> if (x >= 128) x - 256 else x
                "abs" -> Math.abs(x)
                else -> x
            }
        }
    }

    private fun skip() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun parseExpr(): Node {
        var left = parseTerm()
        while (true) {
            skip()
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) {
                val op = src[pos++]
                left = Node.Bin(op, left, parseTerm())
            } else return left
        }
    }

    private fun parseTerm(): Node {
        var left = parseFactor()
        while (true) {
            skip()
            if (pos < src.length && (src[pos] == '*' || src[pos] == '/')) {
                val op = src[pos++]
                left = Node.Bin(op, left, parseFactor())
            } else return left
        }
    }

    private fun parseFactor(): Node {
        skip()
        if (pos >= src.length) throw IllegalArgumentException("Unexpected end in $src")
        val c = src[pos]
        if (c == '-') {
            pos++
            return Node.Neg(parseFactor())
        }
        if (c == '(') {
            pos++
            val e = parseExpr()
            skip()
            expect(')')
            return e
        }
        if (c == '{') {
            pos++
            skip()
            val v = src[pos++].uppercaseChar() - 'A'
            skip()
            expect(':')
            val start = pos
            while (pos < src.length && src[pos].isDigit()) pos++
            val bit = src.substring(start, pos).toInt()
            skip()
            expect('}')
            return Node.Bit(v, bit)
        }
        if (c.isDigit() || c == '.') {
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            return Node.Num(src.substring(start, pos).toDouble())
        }
        if (c.isLetter()) {
            val start = pos
            while (pos < src.length && src[pos].isLetter()) pos++
            val word = src.substring(start, pos)
            skip()
            if (pos < src.length && src[pos] == '(') {
                pos++
                val arg = parseExpr()
                skip()
                expect(')')
                return Node.Call(word, arg)
            }
            if (word.length == 1 && word[0].uppercaseChar() in 'A'..'H') return Node.Var(word[0].uppercaseChar() - 'A')
            throw IllegalArgumentException("Unknown name $word in $src")
        }
        throw IllegalArgumentException("Unexpected '$c' in $src")
    }

    private fun expect(ch: Char) {
        if (pos >= src.length || src[pos] != ch) throw IllegalArgumentException("Expected '$ch' in $src")
        pos++
    }
}
