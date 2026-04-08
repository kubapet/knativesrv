import com.benasher44.uuid.uuid4
import kotlinx.cinterop.*
import libopenssl.*
import platform.posix.*

class RSAKeyPair private constructor(private val rsa: CValuesRef<RSA>) {
    val keyId = uuid4().toString()

    private val pkey by lazy { memScoped {
            val pkey = EVP_PKEY_new()
            EVP_PKEY_assign(pkey, EVP_PKEY_RSA, rsa)
            pkey
    } }

    val privateKeyPem by lazy { memScoped {
            val privateBIO = BIO_new(BIO_s_mem())
            PEM_write_bio_PrivateKey(privateBIO, pkey, null, null, 0, null, null)
            privateBIO?.toKString()
    } }

    val publicKeyPem by lazy { memScoped {
            val publicBIO = BIO_new(BIO_s_mem())
            PEM_write_bio_PUBKEY(publicBIO, pkey)
            publicBIO?.toKString()
    } }

    val publicExponent by lazy { memScoped {
            bigNumToByteArray(rsa.getPointer(this).pointed.e)
    } }

    val modulus by lazy { memScoped {
            bigNumToByteArray(rsa.getPointer(this).pointed.n)
    } }

    fun rs256Signature(input: String): UByteArray = memScoped {
        val sha256 = allocArray<UByteVar>(SHA256_DIGEST_LENGTH)
        SHA256(input.cstr.ptr.reinterpret(), input.length.convert(), sha256)

        val signature = allocArray<UByteVar>(RSA_size(rsa))
        val length = alloc<UIntVar>()
        RSA_sign(NID_sha256, sha256, SHA256_DIGEST_LENGTH, signature, length.ptr, rsa)

        return signature.readBytes(length.value.convert()).asUByteArray()
    }

    fun verifyRs256Signature(input: String, signature: UByteArray): Boolean = memScoped {
        val sha256 = allocArray<UByteVar>(SHA256_DIGEST_LENGTH)
        SHA256(input.cstr.ptr.reinterpret(), input.length.convert(), sha256)

        RSA_verify(
            NID_sha256,
            sha256,
            SHA256_DIGEST_LENGTH,
            signature.toCValues().ptr.reinterpret(),
            signature.size.convert(),
            rsa
        ) == 1
    }

    private fun bigNumToByteArray(bigNum: CValuesRef<BIGNUM>?): UByteArray = memScoped {
        val length = (BN_num_bits(bigNum) + 7) / 8
        val buffer = allocArray<UByteVar>(length)
        BN_bn2bin(bigNum, buffer)
        buffer.readBytes(length).asUByteArray()
    }

    private fun CPointer<BIO>.toKString() = memScoped {
        val publicKeyLen = BIO_ctrl(this@toKString, BIO_CTRL_PENDING, 0, null)
        val publicKeyChar = allocArray<ByteVar>(publicKeyLen)
        BIO_read(this@toKString, publicKeyChar, publicKeyLen.convert())
        publicKeyChar.toKString()
    }

    companion object {
        fun generateNew(): RSAKeyPair {
            val bn = BN_new()
            BN_set_word(bn, RSA_F4.convert())

            val rsa = RSA_new()
            RSA_generate_key_ex(rsa, 2048, bn, null)

            return RSAKeyPair(rsa!!)
        }

        fun fromB64PublicExponentAndModulus(publicExponent: String, modulus: String): RSAKeyPair = memScoped {
            val e = Base64.decodeToUByteArray(publicExponent)
            val n = Base64.decodeToUByteArray(modulus)
            val rsa = RSA_new()

            rsa?.pointed?.e = BN_bin2bn(e.toCValues().ptr.reinterpret(), e.size, null)
            rsa?.pointed?.n = BN_bin2bn(n.toCValues().ptr.reinterpret(), n.size, null)

            return RSAKeyPair(rsa!!)
        }
    }
}

object Base64 {
    fun decodeToByteArray(input: String): ByteArray = memScoped {
        val missingPaddingLength = if (input.length % 4 != 0) 4 - (input.length % 4) else 0
        val b64message = input
            .replace("-", "+")
            .replace("_", "/")
            .plus((1..missingPaddingLength).joinToString("") { "=" })

        val decodeLen = (b64message.length * 0.75 - missingPaddingLength).toInt()
        val buffer = allocArray<ByteVar>(decodeLen)
        val stream = fmemopen(b64message.cstr.ptr, strlen(b64message), "r")

        val b64 = BIO_new(BIO_f_base64())
        var bio = BIO_new_fp(stream?.reinterpret(), BIO_NOCLOSE)
        bio = BIO_push(b64, bio)

        BIO_set_flags(bio, BIO_FLAGS_BASE64_NO_NL)
        BIO_read(bio, buffer, strlen(b64message).convert())

        BIO_free_all(bio)
        fclose(stream)

        buffer.readBytes(decodeLen)
    }

    fun decodeToString(input: String): String = decodeToByteArray(input).decodeToString()
    fun decodeToUByteArray(input: String): UByteArray = decodeToByteArray(input).asUByteArray()

    fun encode(input: CValues<*>, length: Int): String = memScoped {
        val encodedSize: Int = 4 * ceil((length.toDouble() / 3)).toInt()
        val buffer = allocArray<ByteVar>(encodedSize + 1)
        val stream = fmemopen(buffer, (encodedSize + 1).convert(), "w")

        val b64 = BIO_new(BIO_f_base64())
        var bio = BIO_new_fp(stream?.reinterpret(), BIO_NOCLOSE)
        bio = BIO_push(b64, bio)

        BIO_set_flags(bio, BIO_FLAGS_BASE64_NO_NL)
        BIO_write(bio, input, length)
        BIO_ctrl(bio, BIO_CTRL_FLUSH, 0, null)
        BIO_free_all(bio)
        fclose(stream)

        return buffer.toKString()
            .replace("+", "-")
            .replace("/", "_")
            .replace("=", "")
    }

    fun encode(input: String): String = encode(input.cstr, strlen(input).convert())
    fun encode(input: UByteArray): String = encode(input.toCValues(), input.size)
}