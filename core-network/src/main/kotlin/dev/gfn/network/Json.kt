package dev.gfn.network

/**
 * 纯 Kotlin JSON 读写器。
 *
 * 目的不是替代完整 JSON 库，而是让协议核心在没有 Android SDK、没有联网下载依赖的环境中
 * 也能进行确定性的编译与 fixture 测试。只实现 GFN 当前响应/请求需要的 JSON 类型。
 */
object Json {
    sealed interface Value {
        data class Str(val value: String) : Value
        data class Num(val value: String) : Value
        data class Bool(val value: Boolean) : Value
        data class Obj(val value: Map<String, Value>) : Value
        data class Arr(val value: List<Value>) : Value
        data object Null : Value
    }

    fun parse(text: String): Value {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        require(parser.isEnd()) { "JSON 尾部存在额外数据" }
        return value
    }

    fun parseObject(text: String): Map<String, Value> =
        (parse(text) as? Value.Obj)?.value ?: error("JSON 根节点不是 object")

    fun parseArray(text: String): List<Value> =
        (parse(text) as? Value.Arr)?.value ?: error("JSON 根节点不是 array")

    fun stringify(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is CharSequence -> quote(value.toString())
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
            require(key is String) { "JSON object key 必须是 String" }
            "${quote(key)}:${stringify(item)}"
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { stringify(it) }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { stringify(it) }
        else -> error("不支持的 JSON 类型：${value::class.qualifiedName}")
    }

    fun Map<String, Value>.string(name: String): String? = (this[name] as? Value.Str)?.value
    fun Map<String, Value>.int(name: String): Int? = when (val value = this[name]) {
        is Value.Num -> value.value.toIntOrNull()
        else -> null
    }
    fun Map<String, Value>.long(name: String): Long? = when (val value = this[name]) {
        is Value.Num -> value.value.toLongOrNull()
        else -> null
    }
    fun Map<String, Value>.boolean(name: String): Boolean? = (this[name] as? Value.Bool)?.value
    fun Map<String, Value>.obj(name: String): Map<String, Value>? = (this[name] as? Value.Obj)?.value
    fun Map<String, Value>.array(name: String): List<Value>? = (this[name] as? Value.Arr)?.value

    fun Value.asObject(): Map<String, Value>? = (this as? Value.Obj)?.value
    fun Value.asArray(): List<Value>? = (this as? Value.Arr)?.value
    fun Value.asString(): String? = (this as? Value.Str)?.value
    fun Value.asBoolean(): Boolean? = (this as? Value.Bool)?.value
    fun Value.asInt(): Int? = (this as? Value.Num)?.value?.toIntOrNull()

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private class Parser(private val source: String) {
        private var index = 0

        fun isEnd(): Boolean = index >= source.length
        fun skipWhitespace() { while (!isEnd() && source[index].isWhitespace()) index += 1 }

        fun parseValue(): Value {
            skipWhitespace()
            check(!isEnd()) { "JSON 意外结束" }
            return when (source[index]) {
                '"' -> Value.Str(parseString())
                '{' -> Value.Obj(parseObjectInternal())
                '[' -> Value.Arr(parseArrayInternal())
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
            if (peek('}')) { index += 1; return emptyMap() }
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

        private fun parseArrayInternal(): List<Value> {
            expectChar('[')
            skipWhitespace()
            if (peek(']')) { index += 1; return emptyList() }
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
