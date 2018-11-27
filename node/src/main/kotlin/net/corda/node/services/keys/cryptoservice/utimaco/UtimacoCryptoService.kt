package net.corda.node.services.keys.cryptoservice.utimaco

import CryptoServerCXI.CryptoServerCXI
import CryptoServerJCE.CryptoServerProvider
import com.typesafe.config.ConfigFactory
import net.corda.core.crypto.Crypto
import net.corda.nodeapi.internal.config.parseAs
import net.corda.nodeapi.internal.cryptoservice.CryptoService
import net.corda.nodeapi.internal.cryptoservice.CryptoServiceException
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.operator.ContentSigner
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Path
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.util.*
import kotlin.reflect.full.memberProperties

/**
 * Implementation of CryptoService for the Utimaco HSM.
 */
class UtimacoCryptoService(private val cryptoServerProvider: CryptoServerProvider, private val keyConfig: KeyGenerationConfiguration, private val authThreshold: Int, private val auth: () -> UtimacoCredentials) : CryptoService {

    private val keyStore: KeyStore
    private val keyTemplate: CryptoServerCXI.KeyAttributes

    init {
        try {
            keyTemplate = toKeyTemplate(keyConfig)
            authenticate(auth())
            val authState = cryptoServerProvider.cryptoServer.authState
            require((authState and 0x0000000F) >= authThreshold) {
                "Insufficient authentication: auth state is $authState, at least $authThreshold is required."
            }
            keyStore = KeyStore.getInstance("CryptoServer", cryptoServerProvider)
            keyStore.load(null, null)
        } catch (e: CryptoServerAPI.CryptoServerException) {
            throw UtimacoHSMException(HsmErrors.errors[e.ErrorCode], e)
        }
    }

    private inline fun <T> withAuthentication(block: () -> T): T {
        return withErrorMapping {
            if (cryptoServerProvider.cryptoServer.authState and 0x0000000F >= authThreshold) {
                block()
            } else {
                authenticate(auth())
                block()
            }
        }
    }

    private inline fun <T> withErrorMapping(block: () -> T): T {
        try {
            return block()
        } catch (e: CryptoServerAPI.CryptoServerException) {
            throw UtimacoHSMException(HsmErrors.errors[e.ErrorCode], e)
        }
    }

    override fun generateKeyPair(alias: String, schemeNumberID: Int): PublicKey {
        return generateKeyPair(alias, schemeNumberID, keyTemplate)
    }

    override fun containsKey(alias: String): Boolean {
        try {
            return withAuthentication {
                keyStore.containsAlias(alias)
            }
        } catch (e: CryptoServerAPI.CryptoServerException) {
            HsmErrors.errors[e.ErrorCode]
            throw UtimacoHSMException(HsmErrors.errors[e.ErrorCode], e)
        }
    }

    override fun getPublicKey(alias: String): PublicKey? {
        try {
            return withAuthentication {
                keyStore.getCertificate(alias)?.publicKey?.let {
                    KeyFactory.getInstance(it.algorithm).generatePublic(X509EncodedKeySpec(it.encoded))
                }
            }
        } catch (e: CryptoServerAPI.CryptoServerException) {
            HsmErrors.errors[e.ErrorCode]
            throw UtimacoHSMException(HsmErrors.errors[e.ErrorCode], e)
        }
    }

    override fun sign(alias: String, data: ByteArray): ByteArray {
        try {
            return withAuthentication {
                (keyStore.getKey(alias, null) as PrivateKey?)?.let {
                    val algorithm = if (it.algorithm == "RSA") {
                        "SHA256withRSA"
                    } else {
                        "SHA256withECDSA"
                    }
                    val signature = Signature.getInstance(algorithm, cryptoServerProvider)
                    signature.initSign(it)
                    signature.update(data)
                    signature.sign()
                } ?: throw CryptoServiceException("No key found for alias $alias")
            }
        } catch (e: CryptoServerAPI.CryptoServerException) {
            HsmErrors.errors[e.ErrorCode]
            throw UtimacoHSMException(HsmErrors.errors[e.ErrorCode], e)
        }
    }

    override fun getSigner(alias: String): ContentSigner {
        return object : ContentSigner {
            private val publicKey: PublicKey = getPublicKey(alias) ?: throw CryptoServiceException("No key found for alias $alias")
            private val sigAlgID: AlgorithmIdentifier = Crypto.findSignatureScheme(publicKey).signatureOID

            private val baos = ByteArrayOutputStream()
            override fun getAlgorithmIdentifier(): AlgorithmIdentifier = sigAlgID
            override fun getOutputStream(): OutputStream = baos
            override fun getSignature(): ByteArray = sign(alias, baos.toByteArray())
        }
    }

    fun generateKeyPair(alias: String, schemeId: Int, keyTemplate: CryptoServerCXI.KeyAttributes): PublicKey {
        return withAuthentication {
            val keyAttributes = attributesForScheme(keyTemplate, schemeId)
            keyAttributes.name = alias
            val overwrite = if (keyConfig.keyOverride) CryptoServerCXI.FLAG_OVERWRITE else 0
            cryptoServerProvider.cryptoServer.generateKey(overwrite, keyAttributes, keyConfig.keyGenMechanism)
            getPublicKey(alias) ?: throw CryptoServiceException("Key generation for alias $alias succeeded, but key could not be accessed afterwards.")
        }
    }

    fun logOff() {
        cryptoServerProvider.logoff()
    }

    private fun authenticate(credentials: UtimacoCredentials) {
        if (credentials.keyFile != null) {
            cryptoServerProvider.loginSign(credentials.username, credentials.keyFile.toFile().absolutePath, String(credentials.password))
        } else {
            cryptoServerProvider.loginPassword(credentials.username, credentials.password)
        }
    }

    class UtimacoHSMException(message: String?, cause: Throwable? = null) : CryptoServiceException(message, cause)

    data class UtimacoCredentials(val username: String, val password: ByteArray, val keyFile: Path? = null)

    data class UtimacoConfig(
            val provider: ProviderConfig,
            val keyGeneration: KeyGenerationConfiguration = KeyGenerationConfiguration(),
            val authThreshold: Int = 1,
            val authentication: Credentials,
            val keyFile: Path? = null
    )

    data class ProviderConfig(
            val host: String,
            val port: Int,
            val connectionTimeout: Int = 30000,
            val timeout: Int = 60000,
            val endSessionOnShutdown: Boolean = true,
            val keepSessionAlive: Boolean = false,
            val keyGroup: String = "*",
            val keySpecifier: Int = -1,
            val storeKeysExternal: Boolean = false
    )

    data class KeyGenerationConfiguration(
            val keyGroup: String = "*",
            val keySpecifier: Int = -1,
            val keyOverride: Boolean = false,
            val keyExport: Boolean = false,
            val keyGenMechanism: Int = 4
    )

    data class Credentials(val username: String, val password: String)

    /**
     * Taken from network-services.
     * Configuration class for [CryptoServerProvider]
     * Currently not supported: DefaultUser,KeyStorePath,LogFile,LogLevel,LogSize
     */
    internal data class CryptoServerProviderConfig(
            val Device: String,
            val ConnectionTimeout: Int,
            val Timeout: Int,
            val EndSessionOnShutdown: Int, // todo does this actually exist? docs don't mention it
            val KeepSessionAlive: Int,
            val KeyGroup: String,
            val KeySpecifier: Int,
            val StoreKeysExternal: Boolean
    )

    private fun attributesForScheme(keyTemplate: CryptoServerCXI.KeyAttributes, schemeId: Int): CryptoServerCXI.KeyAttributes {
        if (schemeId !in keyAttributeForScheme.keys) {
            throw NoSuchAlgorithmException("No mapping for scheme ID $schemeId.")
        }
        val schemeAttributes = keyAttributeForScheme[schemeId]!!
        return CryptoServerCXI.KeyAttributes().apply {
            specifier = keyTemplate.specifier
            group = keyTemplate.group
            export = keyTemplate.export
            algo = schemeAttributes.algo
            curve = schemeAttributes.curve
            size = schemeAttributes.size
        }
    }

    companion object {
        val DEFAULT_IDENTITY_SIGNATURE_SCHEME = Crypto.ECDSA_SECP256R1_SHA256

        private val keyAttributeForScheme: Map<Int, CryptoServerCXI.KeyAttributes> = mapOf(
                Crypto.ECDSA_SECP256R1_SHA256.schemeNumberID to CryptoServerCXI.KeyAttributes().apply {
                    algo = CryptoServerCXI.KEY_ALGO_ECDSA
                    setCurve("secp256r1")
                },
                Crypto.ECDSA_SECP256K1_SHA256.schemeNumberID to CryptoServerCXI.KeyAttributes().apply {
                    algo = CryptoServerCXI.KEY_ALGO_ECDSA
                    setCurve("secp256k1")
                },
                Crypto.RSA_SHA256.schemeNumberID to CryptoServerCXI.KeyAttributes().apply {
                    algo = CryptoServerCXI.KEY_ALGO_RSA
                    size = Crypto.RSA_SHA256.keySize!!
                }
        )

        fun parseConfigFile(configFile: Path): UtimacoConfig {
            val config = ConfigFactory.parseFile(configFile.toFile())
            return config.parseAs(UtimacoConfig::class)
        }

        /**
         * Username and password are stored in files, base64-encoded
         */
        fun fileBasedAuth(usernameFile: Path, passwordFile: Path): () -> UtimacoCredentials = {
            val username = String(Base64.getDecoder().decode(usernameFile.toFile().readLines().first()))
            val pw = if (usernameFile == passwordFile) {
                Base64.getDecoder().decode(passwordFile.toFile().readLines().get(1))
            } else {
                Base64.getDecoder().decode(passwordFile.toFile().readLines().get(0))
            }
            UtimacoCredentials(username, pw)
        }

        fun fromConfigurationFile(configFile: Path?): UtimacoCryptoService {
            val config = parseConfigFile(configFile!!)
            return fromConfig(config) { UtimacoCredentials(config.authentication.username, config.authentication.password.toByteArray(), config.keyFile) }
        }

        fun fromConfig(configuration: UtimacoConfig, auth: () -> UtimacoCredentials): UtimacoCryptoService {
            val providerConfig = toCryptoServerProviderConfig(configuration.provider)
            val cryptoServerProvider = createProvider(providerConfig)
            return UtimacoCryptoService(cryptoServerProvider, configuration.keyGeneration, configuration.authThreshold, auth)
        }

        /**
         * Note that some attributes cannot be determined at this point, as they depend on the scheme ID
         */
        private fun toKeyTemplate(config: KeyGenerationConfiguration): CryptoServerCXI.KeyAttributes {
            return CryptoServerCXI.KeyAttributes().apply {
                specifier = config.keySpecifier
                group = config.keyGroup
                export = if (config.keyExport) 1 else 0
            }
        }

        private fun toCryptoServerProviderConfig(config: ProviderConfig): CryptoServerProviderConfig {
            return CryptoServerProviderConfig(
                    "${config.port}@${config.host}",
                    config.connectionTimeout,
                    config.timeout,
                    if (config.endSessionOnShutdown) 1 else 0,
                    if (config.keepSessionAlive) 1 else 0,
                    config.keyGroup,
                    config.keySpecifier,
                    config.storeKeysExternal
            )
        }

        /**
         * Taken from network-services.
         * Creates an instance of [CryptoServerProvider] configured accordingly to the passed configuration.
         *
         * @param config crypto server provider configuration.
         *
         * @return preconfigured instance of [CryptoServerProvider]
         */
        private fun createProvider(config: CryptoServerProviderConfig): CryptoServerProvider {
            val cfgBuffer = ByteArrayOutputStream()
            val writer = cfgBuffer.writer(Charsets.UTF_8)
            for (property in CryptoServerProviderConfig::class.memberProperties) {
                writer.write("${property.name} = ${property.get(config)}\n")
            }
            writer.close()
            val cfg = cfgBuffer.toByteArray().inputStream()
            return CryptoServerProvider(cfg)
        }
    }
}

// This code (incl. the hsm_errors file) is duplicated with the Network-Management module
object HsmErrors {
    val errors: Map<Int, String> by lazy(HsmErrors::load)
    fun load(): Map<Int, String> {
        val errors = HashMap<Int, String>()
        val hsmErrorsStream = HsmErrors::class.java.getResourceAsStream("hsm_errors")
        hsmErrorsStream.bufferedReader().lines().reduce(null) { previous, current ->
            if (previous == null) {
                current
            } else {
                errors[java.lang.Long.decode(previous).toInt()] = current
                null
            }
        }
        return errors
    }
}