package bosca.security.encryption

import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.util.Arrays
import kotlin.io.encoding.Base64

// kotlin port of Argon2EncodingUtils
object Argon2EncodingUtils {

    private val b64encoder = Base64.withPadding(Base64.PaddingOption.ABSENT)

    private val b64decoder = Base64.withPadding(Base64.PaddingOption.ABSENT)

    fun encode(hash: ByteArray, parameters: Argon2Parameters): String {
        val stringBuilder = StringBuilder()
        val type = when (parameters.type) {
            Argon2Parameters.ARGON2_d -> $$"$argon2d"
            Argon2Parameters.ARGON2_i -> $$"$argon2i"
            Argon2Parameters.ARGON2_id -> $$"$argon2id"
            else -> throw IllegalArgumentException("Invalid algorithm type: " + parameters.type)
        }
        stringBuilder.append(type)
        stringBuilder.append("\$v=")
            .append(parameters.version)
            .append("\$m=")
            .append(parameters.memory)
            .append(",t=")
            .append(parameters.iterations)
            .append(",p=")
            .append(parameters.lanes)
        if (parameters.salt != null) {
            stringBuilder.append("$").append(b64encoder.encode(parameters.salt))
        }
        stringBuilder.append("$").append(b64encoder.encode(hash))
        return stringBuilder.toString()
    }

    fun decode(encodedHash: String): Argon2Hash {
        var paramsBuilder: Argon2Parameters.Builder
        val parts = encodedHash.split("$")
        if (parts.size < 4) {
            throw IllegalArgumentException("Invalid encoded Argon2-hash")
        }
        var currentPart = 1
        paramsBuilder = when (parts[currentPart++]) {
            "argon2d" -> Argon2Parameters.Builder(Argon2Parameters.ARGON2_d)
            "argon2i" -> Argon2Parameters.Builder(Argon2Parameters.ARGON2_i)
            "argon2id" -> Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            else -> throw IllegalArgumentException("Invalid algorithm type: " + parts[1])
        }
        if (parts[currentPart].startsWith("v=")) {
            paramsBuilder.withVersion(Integer.parseInt(parts[currentPart].substring(2)))
            currentPart++
        }
        val performanceParams = parts[currentPart++].split(",")
        if (performanceParams.size != 3) {
            throw IllegalArgumentException("Amount of performance parameters invalid")
        }
        if (!performanceParams[0].startsWith("m=")) {
            throw IllegalArgumentException("Invalid memory parameter")
        }
        paramsBuilder.withMemoryAsKB(Integer.parseInt(performanceParams[0].substring(2)))
        if (!performanceParams[1].startsWith("t=")) {
            throw IllegalArgumentException("Invalid iterations parameter")
        }
        paramsBuilder.withIterations(Integer.parseInt(performanceParams[1].substring(2)))
        if (!performanceParams[2].startsWith("p=")) {
            throw IllegalArgumentException("Invalid parallelity parameter")
        }
        paramsBuilder.withParallelism(Integer.parseInt(performanceParams[2].substring(2)))
        val salt = parts[currentPart++]
        paramsBuilder.withSalt(b64decoder.decode(salt))
        val hash = parts[currentPart]
        return Argon2Hash(b64decoder.decode(hash), paramsBuilder.build())
    }

    class Argon2Hash(hash: ByteArray, val parameters: Argon2Parameters) {

        val hash = Arrays.clone(hash)
    }

}