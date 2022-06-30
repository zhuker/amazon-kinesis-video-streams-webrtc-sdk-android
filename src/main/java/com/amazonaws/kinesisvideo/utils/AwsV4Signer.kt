package com.amazonaws.kinesisvideo.utils

import android.util.Log
import com.amazonaws.util.BinaryUtils
import com.amazonaws.util.DateUtils
import java.io.UnsupportedEncodingException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.InvalidKeyException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object AwsV4Signer {
    private const val ALGORITHM_AWS4_HMAC_SHA_256 = "AWS4-HMAC-SHA256"
    private const val AWS4_REQUEST_TYPE = "aws4_request"
    private const val SERVICE = "kinesisvideo"
    private const val X_AMZ_ALGORITHM = "X-Amz-Algorithm"
    private const val X_AMZ_CREDENTIAL = "X-Amz-Credential"
    private const val X_AMZ_DATE = "X-Amz-Date"
    private const val X_AMZ_EXPIRES = "X-Amz-Expires"
    private const val X_AMZ_SECURITY_TOKEN = "X-Amz-Security-Token"
    private const val X_AMZ_SIGNATURE = "X-Amz-Signature"
    private const val X_AMZ_SIGNED_HEADERS = "X-Amz-SignedHeaders"
    private const val NEW_LINE_DELIMITER = "\n"
    private const val DATE_PATTERN = "yyyyMMdd"
    private const val TIME_PATTERN = "yyyyMMdd'T'HHmmss'Z'"
    private const val METHOD = "GET"
    private const val SIGNED_HEADERS = "host"

    // Guide - https://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html
    // Implementation based on https://docs.aws.amazon.com/general/latest/gr/sigv4-signed-request-examples.html#sig-v4-examples-get-query-string
    fun sign(
        uri: URI, accessKey: String, secretKey: String,
        sessionToken: String, wssUri: URI, region: String
    ): URI? {
        val dateMilli = Date().time
        val amzDate = getTimeStamp(dateMilli)
        val datestamp = getDateStamp(dateMilli)
        val queryParamsMap =
            buildQueryParamsMap(uri, accessKey, sessionToken, region, amzDate, datestamp)
        val canonicalQuerystring = getCanonicalizedQueryString(queryParamsMap)
        val canonicalRequest = getCanonicalRequest(uri, canonicalQuerystring)
        val stringToSign =
            signString(amzDate, createCredentialScope(region, datestamp), canonicalRequest)
        val signatureKey = getSignatureKey(secretKey, datestamp, region, SERVICE)
        val signature = BinaryUtils.toHex(hmacSha256(stringToSign, signatureKey))
        val signedCanonicalQueryString = "$canonicalQuerystring&$X_AMZ_SIGNATURE=$signature"
        var uriResult: URI? = null
        try {
            uriResult = URI(
                wssUri.scheme,
                wssUri.rawAuthority,
                getCanonicalUri(uri),
                signedCanonicalQueryString,
                null
            )
        } catch (e: URISyntaxException) {
            Log.e("AwsV4Signer", "sign error $e")
        }
        return uriResult
    }

    private fun buildQueryParamsMap(
        uri: URI,
        accessKey: String,
        sessionToken: String,
        region: String,
        amzDate: String,
        datestamp: String
    ): Map<String, String> {
        return buildMap {
            put(X_AMZ_ALGORITHM, ALGORITHM_AWS4_HMAC_SHA_256)
            put(
                X_AMZ_CREDENTIAL,
                urlEncode(accessKey + "/" + createCredentialScope(region, datestamp))
            )
            put(X_AMZ_DATE, amzDate)
            put(X_AMZ_EXPIRES, "299")
            put(X_AMZ_SIGNED_HEADERS, SIGNED_HEADERS)
            if (sessionToken.isNotEmpty()) {
                put(X_AMZ_SECURITY_TOKEN, urlEncode(sessionToken))
            }
            if (!uri.query.isNullOrEmpty()) {
                val params = uri.query.split("&").toTypedArray()
                for (param in params) {
                    val index = param.indexOf('=')
                    if (index > 0) {
                        put(
                            param.substring(0, index),
                            urlEncode(param.substring(index + 1))
                        )
                    }
                }
            }
        }

    }

    private fun createCredentialScope(region: String, datestamp: String): String {
        return StringJoiner("/").add(datestamp).add(region).add(SERVICE).add(AWS4_REQUEST_TYPE)
            .toString()
    }

    @JvmStatic
    fun getCanonicalRequest(uri: URI, canonicalQuerystring: String?): String {
        val payloadHash = "".sha256()
        val canonicalUri = getCanonicalUri(uri)
        val canonicalHeaders = "host:" + uri.host + NEW_LINE_DELIMITER
        return StringJoiner(NEW_LINE_DELIMITER).add(METHOD)
            .add(canonicalUri)
            .add(canonicalQuerystring)
            .add(canonicalHeaders)
            .add(SIGNED_HEADERS)
            .add(payloadHash)
            .toString()
    }

    private fun getCanonicalUri(uri: URI): String {
        return uri.path.orEmpty().ifEmpty { "/" }
    }

    fun ByteArray.toHexString(): String {
        val bytes: ByteArray = this
        val sb = StringBuilder(2 * bytes.size)
        for (b in bytes) {
            sb.append(hexDigits[b.toInt() shr 4 and 0xf]).append(hexDigits[b.toInt() and 0xf])
        }
        return sb.toString()
    }

    private val hexDigits = "0123456789abcdef".toCharArray()

    private fun String.sha256(): String {
        val str = this
        val prototype = MessageDigest.getInstance("SHA-256")
        val digest = prototype.digest(str.toByteArray())
        val toHexString = digest.toHexString()
        return toHexString
    }

    @JvmStatic
    fun signString(
        amzDate: String?,
        credentialScope: String?,
        canonicalRequest: String?
    ): String {

        return StringJoiner(NEW_LINE_DELIMITER).add(ALGORITHM_AWS4_HMAC_SHA_256)
            .add(amzDate)
            .add(credentialScope)
            .add(
                canonicalRequest.orEmpty().sha256()
            )
            .toString()
    }

    private fun urlEncode(str: String): String {
        return try {
            URLEncoder.encode(str, StandardCharsets.UTF_8.name())
        } catch (e: UnsupportedEncodingException) {
            throw IllegalArgumentException(e.message, e)
        }
    }

    //  https://docs.aws.amazon.com/general/latest/gr/signature-v4-examples.html#signature-v4-examples-java
    @JvmStatic
    fun hmacSha256(data: String, key: ByteArray?): ByteArray {
        val algorithm = "HmacSHA256"
        val mac: Mac
        return try {
            mac = Mac.getInstance(algorithm)
            mac.init(SecretKeySpec(key, algorithm))
            mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalArgumentException(e.message, e)
        } catch (e: InvalidKeyException) {
            throw IllegalArgumentException(e.message, e)
        }
    }

    //   https://docs.aws.amazon.com/general/latest/gr/signature-v4-examples.html#signature-v4-examples-java
    @JvmStatic
    fun getSignatureKey(
        key: String,
        dateStamp: String,
        regionName: String,
        serviceName: String
    ): ByteArray {
        val kSecret = "AWS4$key".toByteArray(StandardCharsets.UTF_8)
        val kDate = hmacSha256(dateStamp, kSecret)
        val kRegion = hmacSha256(regionName, kDate)
        val kService = hmacSha256(serviceName, kRegion)
        return hmacSha256(AWS4_REQUEST_TYPE, kService)
    }

    private fun getTimeStamp(dateMilli: Long): String {
        return DateUtils.format(TIME_PATTERN, Date(dateMilli))
    }

    private fun getDateStamp(dateMilli: Long): String {
        return DateUtils.format(DATE_PATTERN, Date(dateMilli))
    }

    @JvmStatic
    fun getCanonicalizedQueryString(queryParamsMap: Map<String, String>): String {
        val queryKeys: List<String> = ArrayList(queryParamsMap.keys)
        Collections.sort(queryKeys)
        val builder = StringBuilder()
        for (i in queryKeys.indices) {
            builder.append(queryKeys[i]).append("=").append(queryParamsMap[queryKeys[i]])
            if (queryKeys.size - 1 > i) {
                builder.append("&")
            }
        }
        return builder.toString()
    }
}