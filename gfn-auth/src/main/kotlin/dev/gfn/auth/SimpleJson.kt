package dev.gfn.auth

/**
 * OAuth / userinfo 响应使用的最小 JSON 解析器。
 *
 * 第二版只需要读取标准 JSON object 中的字符串、数字、布尔、null；仍支持嵌套 object/array，
 * 以避免用正则解析 JSON。后续项目可在完整 Gradle/Android 构建环境中切换 kotlinx.serialization。
 */
internal object SimpleJson {
    sealed interface Value {
        data class Str(val value: String) : Value
        data class Num(val value: String) : Value
        data class Bool(val value: Boolean) : Value
        data class Obj(val value: Map<String, Value>) : Value
        data class Arr(val value: List<Value>) : Value
        data object Null : Value
    }

    fun parseObject(text: String): Map<String, Value> {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        require(parser.isEnd()) { "JSON 尾部存在额外数据" }
        return (value as? Value.Obj)?.value ?: error("JSON 根节点不是 object")
    }

    fun Map<String, Value>.string(name: String): String? = (this[name] as? Value.Str)?.value
    fun Map<String, Value>.int(name: String): Int? = when (val value = this[name]) {
        is Value.Num -> value.value.toIntOrNull()
        else -> null
    }
    fun Map<String, Value>.objectValue(name: String): Map<String, Value>? = (this[name] as? Value.Obj)?.value
    fun Map<String, Value>.arrayValue(name: String): List<Value>? = (this[name] as? Value.Arr)?.value

    private class Parser(private val source: String) {
        private var index = 0

        fun isEnd(): Boolean = index >= source.length

        fun skipWhitespace() {
            while (!isEnd() && source[index].isWhitespace()) index += 1
        }

        fun parseValue(): Value {
            skipWhitespace()
            check(!isEnd()) { "JSON 意外结束" }
            return when (source[index]) {
                '"' -> Value.Str(parseString())
                '{' -> Value.Obj(parseObjectInternal())
                '[' -> Value.Arr(parseArray())
                't' -> { expect("true"); Value.Bool(true) }
                'f' -> { expect("false"); Value.Bool(false) }
                'n' -> { expect("null"); Value.Null }
                '-', in '0'..'9' -> Value.Num(parseNumber())
                else -> error("非法 JSON 字符 ${source[index]} @ $index")
            }
        }

        private fun parseObjectInternal(): Map<String, Value> {
            expectChar('{')
            skipWhitespace()
            if (peek('}')) {
                index += 1
                return emptyMap()
            }
            val result = linkedMapOf<String, Value>()
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expectChar(':')
                result[key] = parseValue()
                skipWhitespace()
                when {
                    peek(',') -> index += 1
                    peek('}') -> { index += 1; return result }
                    else -> error("JSON object 缺少 ',' 或 '}' @ $index")
                }
            }
        }

        private fun parseArray(): List<Value> {
            expectChar('[')
            skipWhitespace()
            if (peek(']')) {
                index += 1
                return emptyList()
            }
            val result = mutableListOf<Value>()
            while (true) {
                result += parseValue()
                skipWhitespace()
                when {
                    peek(',') -> index += 1
                    peek(']') -> { index += 1; return result }
                    else -> error("JSON array 缺少 ',' 或 ']' @ $index")
                }
            }
        }

        private fun parseString(): String {
            expectChar('"')
            val out = StringBuilder()
            while (!isEnd()) {
                val c = source[index++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        check(!isEnd()) { "JSON 字符串转义不完整" }
                        when (val escaped = source[index++]) {
                            '"', '\\', '/' -> out.append(escaped)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                check(index + 4 <= source.length) { "Unicode 转义不完整" }
                                val code = source.substring(index, index + 4).toInt(16)
                                out.append(code.toChar())
                                index += 4
                            }
                            else -> error("未知 JSON 转义 \\$escaped")
                        }
                    }
                    else -> out.append(c)
                }
            }
            error("JSON 字符串未闭合")
        }

        private fun parseNumber(): String {
            val start = index
            if (peek('-')) index += 1
            while (!isEnd() && source[index].isDigit()) index += 1
            if (!isEnd() && source[index] == '.') {
                index += 1
                while (!isEnd() && source[index].isDigit()) index += 1
            }
            if (!isEnd() && (source[index] == 'e' || source[index] == 'E')) {
                index += 1
                if (!isEnd() && (source[index] == '+' || source[index] == '-')) index += 1
                while (!isEnd() && source[index].isDigit()) index += 1
            }
            return source.substring(start, index)
        }

        private fun expect(value: String) {
            check(source.startsWith(value, index)) { "期望 $value @ $index" }
            index += value.length
        }

        private fun expectChar(value: Char) {
            skipWhitespace()
            check(!isEnd() && source[index] == value) { "期望 '$value' @ $index" }
            index += 1
        }

        private fun peek(value: Char): Boolean = !isEnd() && source[index] == value
    }
}
