package com.aurelien.ocigrab

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class OciResult(val code: Int, val body: String, val nextPage: String? = null)

object OciClient {

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun call(
        cfg: OciConfig, key: PrivateKey, method: String, service: String,
        pathQuery: String, jsonBody: String? = null
    ): OciResult {
        val host = "$service.${cfg.region}.oraclecloud.com"
        val bytes = jsonBody?.toByteArray(Charsets.UTF_8)
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        val date = fmt.format(Date())

        val signing = StringBuilder(
            "(request-target): ${method.lowercase(Locale.ROOT)} $pathQuery\nhost: $host\ndate: $date"
        )
        var headerList = "(request-target) host date"
        var sha = ""
        if (bytes != null) {
            sha = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes), Base64.NO_WRAP)
            signing.append("\nx-content-sha256: $sha\ncontent-type: application/json\ncontent-length: ${bytes.size}")
            headerList += " x-content-sha256 content-type content-length"
        }

        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update(signing.toString().toByteArray(Charsets.UTF_8))
            sign()
        }
        val sigB64 = Base64.encodeToString(sig, Base64.NO_WRAP)
        val auth = "Signature version=\"1\",keyId=\"${cfg.tenancy}/${cfg.user}/${cfg.fingerprint}\"," +
            "algorithm=\"rsa-sha256\",headers=\"$headerList\",signature=\"$sigB64\""

        val conn = URL("https://$host$pathQuery").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method.uppercase(Locale.ROOT)
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.setRequestProperty("date", date)
            conn.setRequestProperty("accept", "application/json")
            conn.setRequestProperty("authorization", auth)
            if (bytes != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.setRequestProperty("x-content-sha256", sha)
                conn.setRequestProperty("content-type", "application/json")
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return OciResult(code, text, conn.getHeaderField("opc-next-page"))
        } finally {
            conn.disconnect()
        }
    }

    fun explain(r: OciResult): String {
        val msg = try { JSONObject(r.body).optString("message") } catch (e: Exception) { "" }
        val base = if (msg.isNotEmpty()) msg else r.body.take(200)
        return if (r.code == 401)
            "$base → clé publique non ajoutée à ton profil, ou OCID / empreinte / région incorrects"
        else base
    }

    fun availabilityDomains(cfg: OciConfig, key: PrivateKey): OciResult =
        call(cfg, key, "GET", "identity", "/20160918/availabilityDomains?compartmentId=${enc(cfg.tenancy)}")

    fun listImages(cfg: OciConfig, key: PrivateKey, page: String?): OciResult {
        var q = "/20160918/images?compartmentId=${enc(cfg.compartment)}&shape=VM.Standard.A1.Flex" +
            "&lifecycleState=AVAILABLE&sortBy=TIMECREATED&sortOrder=DESC&limit=100"
        if (page != null) q += "&page=${enc(page)}"
        return call(cfg, key, "GET", "iaas", q)
    }

    fun listSubnets(cfg: OciConfig, key: PrivateKey): OciResult =
        call(cfg, key, "GET", "iaas", "/20160918/subnets?compartmentId=${enc(cfg.compartment)}&lifecycleState=AVAILABLE")

    fun launch(cfg: OciConfig, key: PrivateKey): OciResult {
        val body = JSONObject().apply {
            put("availabilityDomain", cfg.availabilityDomain)
            put("compartmentId", cfg.compartment)
            put("displayName", cfg.name)
            put("shape", "VM.Standard.A1.Flex")
            put("shapeConfig", JSONObject().put("ocpus", cfg.ocpus).put("memoryInGBs", cfg.memoryGb))
            put("sourceDetails", JSONObject()
                .put("sourceType", "image")
                .put("imageId", cfg.image)
                .put("bootVolumeSizeInGBs", cfg.bootGb))
            put("createVnicDetails", JSONObject()
                .put("subnetId", cfg.subnet)
                .put("assignPublicIp", true))
            put("metadata", JSONObject().put("ssh_authorized_keys", cfg.sshKey))
        }.toString()
        return call(cfg, key, "POST", "iaas", "/20160918/instances", body)
    }

    fun getInstance(cfg: OciConfig, key: PrivateKey, id: String): OciResult =
        call(cfg, key, "GET", "iaas", "/20160918/instances/${enc(id)}")

    fun publicIp(cfg: OciConfig, key: PrivateKey, instanceId: String): String? {
        val a = call(cfg, key, "GET", "iaas",
            "/20160918/vnicAttachments?compartmentId=${enc(cfg.compartment)}&instanceId=${enc(instanceId)}")
        if (a.code !in 200..299) return null
        val arr = JSONArray(a.body)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("lifecycleState") != "ATTACHED") continue
            val vnicId = o.optString("vnicId")
            if (vnicId.isEmpty()) continue
            val v = call(cfg, key, "GET", "iaas", "/20160918/vnics/${enc(vnicId)}")
            if (v.code in 200..299) {
                val ip = JSONObject(v.body).optString("publicIp", "")
                if (ip.isNotEmpty() && ip != "null") return ip
            }
        }
        return null
    }
}
