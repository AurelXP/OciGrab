package com.aurelien.ocigrab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Opt(val label: String, val id: String, val extra: String = "")

data class OciConfig(
    val tenancy: String, val user: String, val fingerprint: String, val privateKeyPem: String,
    val region: String, val compartment: String, val availabilityDomain: String,
    val subnet: String, val image: String, val sshKey: String,
    val ocpus: Int, val memoryGb: Int, val bootGb: Int, val name: String,
    val intervalSec: Int, val osUser: String
)

object AppLog {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(s: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date())
        lines.addLast("$t  $s")
        while (lines.size > 200) lines.removeFirst()
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")
}

object GrabState {
    @Volatile var running = false
    @Volatile var attempts = 0
    @Volatile var startedAt = 0L
    @Volatile var status = ""
}

object Store {
    private fun p(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun get(c: Context, k: String, d: String = ""): String = p(c).getString(k, d) ?: d
    fun put(c: Context, k: String, v: String) { p(c).edit().putString(k, v).apply() }

    fun saveOpts(c: Context, k: String, list: List<Opt>) {
        val a = JSONArray()
        for (o in list) a.put(JSONObject().put("l", o.label).put("i", o.id).put("e", o.extra))
        put(c, k, a.toString())
    }

    fun loadOpts(c: Context, k: String): List<Opt> {
        val s = get(c, k)
        if (s.isEmpty()) return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Opt(o.optString("l"), o.optString("i"), o.optString("e"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun config(c: Context): OciConfig {
        val tenancy = get(c, "tenancy").trim()
        val pem = get(c, "key").trim()
        var fp = get(c, "fingerprint").trim()
        if (fp.isEmpty() && pem.isNotEmpty()) {
            fp = try { KeyUtil.fingerprintOfPrivatePem(pem) } catch (e: Exception) { "" }
        }
        val preset = (get(c, "preset", "1").toIntOrNull() ?: 1).coerceIn(0, 3)
        val imageOs = get(c, "image_os")
        return OciConfig(
            tenancy = tenancy,
            user = get(c, "user").trim(),
            fingerprint = fp,
            privateKeyPem = pem,
            region = get(c, "region").trim().lowercase(Locale.ROOT),
            compartment = get(c, "compartment").trim().ifEmpty { tenancy },
            availabilityDomain = get(c, "ad").trim(),
            subnet = get(c, "subnet").trim(),
            image = get(c, "image").trim(),
            sshKey = get(c, "ssh").trim(),
            ocpus = preset + 1,
            memoryGb = 6 * (preset + 1),
            bootGb = (get(c, "boot", "50").trim().toIntOrNull() ?: 50).coerceAtLeast(47),
            name = get(c, "name", "arm-free").trim().ifEmpty { "arm-free" },
            intervalSec = (get(c, "interval", "60").trim().toIntOrNull() ?: 60).coerceAtLeast(10),
            osUser = if (imageOs.contains("Ubuntu", ignoreCase = true)) "ubuntu" else "opc"
        )
    }

    fun missingForLogin(cfg: OciConfig): List<String> {
        val m = mutableListOf<String>()
        if (cfg.tenancy.isEmpty()) m.add("Tenancy OCID")
        if (cfg.user.isEmpty()) m.add("User OCID")
        if (cfg.region.isEmpty()) m.add("Région")
        if (cfg.privateKeyPem.isEmpty()) m.add("Clé privée API")
        if (cfg.fingerprint.isEmpty()) m.add("Empreinte (clé API invalide ?)")
        return m
    }

    fun missingForLaunch(cfg: OciConfig): List<String> {
        val m = missingForLogin(cfg).toMutableList()
        if (cfg.availabilityDomain.isEmpty()) m.add("Domaine de disponibilité (clique « Se connecter »)")
        if (cfg.image.isEmpty()) m.add("Système d'exploitation")
        if (cfg.subnet.isEmpty()) m.add("Sous-réseau")
        if (cfg.sshKey.isEmpty()) m.add("Clé publique SSH")
        return m
    }
}
