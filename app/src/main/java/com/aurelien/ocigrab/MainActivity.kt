package com.aurelien.ocigrab

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray

class OptSpinner(private val ctx: Context, private val prefKey: String) {
    val view = Spinner(ctx)
    var opts: List<Opt> = emptyList()
        private set

    fun set(list: List<Opt>) {
        opts = list
        view.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, list.map { it.label })
        val idx = list.indexOfFirst { it.id == Store.get(ctx, prefKey) }
        if (idx >= 0) view.setSelection(idx)
    }

    fun selected(): Opt? = opts.getOrNull(view.selectedItemPosition)

    fun save() {
        selected()?.let { Store.put(ctx, prefKey, it.id) }
    }
}

class MainActivity : Activity() {

    private val REQ_EXPORT = 42
    private val handler = Handler(Looper.getMainLooper())
    private val edits = mutableMapOf<String, EditText>()
    private lateinit var root: LinearLayout
    private lateinit var logView: TextView
    private lateinit var infoView: TextView
    private lateinit var adminView: TextView
    private lateinit var apiPubView: TextView
    private lateinit var adSp: OptSpinner
    private lateinit var imageSp: OptSpinner
    private lateinit var subnetSp: OptSpinner
    private lateinit var presetSp: Spinner
    private var pendingContent = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun ui(block: () -> Unit) = runOnUiThread { block() }

    private fun bg(label: String, block: () -> Unit) {
        AppLog.add(label)
        Thread {
            try {
                block()
            } catch (e: Exception) {
                AppLog.add("Erreur : ${e.javaClass.simpleName} ${e.message ?: ""}")
            }
        }.start()
    }

    // ---------- helpers d'interface ----------

    private fun section(t: String) {
        root.addView(TextView(this).apply {
            text = t
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(22), 0, dp(4))
        })
    }

    private fun note(t: String) {
        root.addView(TextView(this).apply {
            text = t
            textSize = 12f
            setPadding(0, 0, 0, dp(6))
        })
    }

    private fun field(key: String, hint: String, def: String = "", multiline: Boolean = false): EditText {
        val e = EditText(this).apply {
            this.hint = hint
            setText(Store.get(this@MainActivity, key, def))
            textSize = 13f
            if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 2
                maxLines = 5
            } else {
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine(true)
            }
        }
        edits[key] = e
        root.addView(e)
        return e
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        root.addView(b)
        return b
    }

    private fun label(t: String) {
        root.addView(TextView(this).apply {
            text = t
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun copy(text: String) {
        if (text.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("oci", text))
        AppLog.add("Copié dans le presse-papiers.")
    }

    private fun export(name: String, content: String) {
        if (content.isBlank()) {
            AppLog.add("Rien à télécharger : génère ou saisis d'abord la clé.")
            return
        }
        pendingContent = content
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_TITLE, name)
        startActivityForResult(i, REQ_EXPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_EXPORT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(pendingContent.toByteArray(Charsets.UTF_8)) }
                AppLog.add("Fichier enregistré.")
            } catch (e: Exception) {
                AppLog.add("Échec de l'enregistrement : ${e.message}")
            }
            pendingContent = ""
        }
    }

    // ---------- écran ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(48), dp(16), dp(32))
        }
        root.addView(TextView(this).apply {
            text = "OCI Grab — machine ARM Always Free"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
        })

        // 1. Compte
        section("1. Compte Oracle")
        note("Génère une clé API, ajoute la clé publique dans la console Oracle (Profil › Clés API › Ajouter une clé API › Coller une clé publique), puis colle ton Tenancy OCID et ton User OCID ici.")
        field("tenancy", "Tenancy OCID (ocid1.tenancy...)")
        field("user", "User OCID (ocid1.user...)")
        field("region", "Région (ex : eu-paris-1)")
        field("fingerprint", "Empreinte de la clé API (auto)")
        field("key", "Clé privée API (PEM)", multiline = true)
        apiPubView = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = Store.get(this@MainActivity, "api_pub")
        }
        button("Générer une clé API") { genApiKey() }
        root.addView(apiPubView)
        button("Copier la clé publique API") { copy(apiPubView.text.toString()) }
        button("Télécharger la clé publique API") { export("oci_api_public.pem", apiPubView.text.toString()) }
        button("Télécharger la clé privée API") { export("oci_api_private.pem", edits["key"]!!.text.toString()) }
        button("🔑 Se connecter à Oracle") { connect() }

        // 2. Configuration
        section("2. Configuration de la machine")
        field("compartment", "Compartment OCID (vide = racine)")
        label("Domaine de disponibilité")
        adSp = OptSpinner(this, "ad")
        root.addView(adSp.view)
        label("Système d'exploitation")
        imageSp = OptSpinner(this, "image")
        root.addView(imageSp.view)
        label("Sous-réseau")
        subnetSp = OptSpinner(this, "subnet")
        root.addView(subnetSp.view)
        label("Taille (quota Always Free : 4 cœurs / 24 Go max)")
        presetSp = Spinner(this)
        presetSp.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("1 cœur · 6 Go de RAM", "2 cœurs · 12 Go de RAM", "3 cœurs · 18 Go de RAM", "4 cœurs · 24 Go de RAM")
        )
        presetSp.setSelection((Store.get(this, "preset", "1").toIntOrNull() ?: 1).coerceIn(0, 3))
        root.addView(presetSp)
        field("boot", "Disque de démarrage (Go, minimum 47)", "50")
        field("name", "Nom de l'instance", "arm-free")
        field("interval", "Délai entre deux tentatives (secondes, min 10)", "60")

        adSp.set(Store.loadOpts(this, "cache_ad"))
        imageSp.set(Store.loadOpts(this, "cache_image"))
        subnetSp.set(Store.loadOpts(this, "cache_subnet"))

        // 3. SSH
        section("3. Accès à distance (SSH)")
        note("Génère une paire de clés (ou colle ta clé publique), télécharge la clé privée et garde-la : sans elle tu ne pourras pas te connecter à la machine.")
        field("ssh", "Clé publique SSH", multiline = true)
        button("Générer une paire de clés SSH") { genSsh() }
        button("Télécharger la clé privée SSH") { export("oci_ssh_key.pem", Store.get(this, "ssh_priv")) }
        button("Télécharger la clé publique SSH") { export("oci_ssh_key.pub", edits["ssh"]!!.text.toString()) }

        // 4. Recherche
        section("4. Recherche de la machine")
        button("▶ Démarrer la recherche") { startSearch() }
        button("■ Arrêter") {
            startService(Intent(this, GrabService::class.java).setAction("STOP"))
        }
        infoView = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(infoView)
        button("Rafraîchir l'IP") { refreshIp() }
        button("Copier la commande SSH") { copy(sshCommand()) }

        // 5. Administrateur / batterie
        section("5. Protection et arrière-plan")
        adminView = TextView(this).apply { textSize = 13f }
        root.addView(adminView)
        button("Activer l'administrateur appareil") {
            val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComp())
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Empêche la désinstallation de l'appli sans désactiver d'abord l'administrateur."
                )
            startActivity(i)
        }
        button("Désactiver l'administrateur appareil") {
            dpm().removeActiveAdmin(adminComp())
        }
        button("Autoriser en arrière-plan (batterie)") {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }

        // Journal
        section("Journal")
        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // ---------- actions ----------

    private fun save() {
        for ((k, e) in edits) Store.put(this, k, e.text.toString())
        adSp.save()
        imageSp.save()
        subnetSp.save()
        Store.put(this, "preset", presetSp.selectedItemPosition.toString())
        Store.put(this, "image_os", imageSp.selected()?.extra ?: Store.get(this, "image_os"))
    }

    private fun genApiKey() {
        bg("Génération de la clé API (RSA 2048)…") {
            val g = KeyUtil.generate(2048, "oci-api")
            Store.put(this, "api_pub", g.publicPem)
            Store.put(this, "key", g.privatePem)
            Store.put(this, "fingerprint", g.fingerprint)
            ui {
                edits["key"]!!.setText(g.privatePem)
                edits["fingerprint"]!!.setText(g.fingerprint)
                apiPubView.text = g.publicPem
            }
            AppLog.add("Clé API générée. Copie la clé publique et ajoute-la dans la console Oracle.")
        }
    }

    private fun genSsh() {
        bg("Génération de la paire SSH (RSA 3072, quelques secondes)…") {
            val g = KeyUtil.generate(3072, "oci-arm")
            Store.put(this, "ssh_priv", g.privatePem)
            Store.put(this, "ssh", g.openSshPublic)
            ui { edits["ssh"]!!.setText(g.openSshPublic) }
            AppLog.add("Paire SSH générée. Télécharge la clé privée avant de lancer la recherche.")
        }
    }

    private fun connect() {
        save()
        val cfg = Store.config(this)
        val miss = Store.missingForLogin(cfg)
        if (miss.isNotEmpty()) {
            AppLog.add("Il manque : " + miss.joinToString(", "))
            return
        }
        bg("Connexion à Oracle (${cfg.region})…") {
            val key = KeyUtil.loadPrivate(cfg.privateKeyPem)

            val r = OciClient.availabilityDomains(cfg, key)
            if (r.code !in 200..299) {
                AppLog.add("⛔ Connexion refusée (${r.code}) : " + OciClient.explain(r))
                return@bg
            }
            val ja = JSONArray(r.body)
            val ads = (0 until ja.length()).map {
                val n = ja.getJSONObject(it).optString("name")
                Opt(n, n)
            }

            // Systèmes d'exploitation compatibles avec la forme ARM
            val seen = LinkedHashMap<String, Opt>()
            var page: String? = null
            for (p in 0 until 6) {
                val ri = OciClient.listImages(cfg, key, page)
                if (ri.code !in 200..299) {
                    AppLog.add("Images : erreur ${ri.code} : " + OciClient.explain(ri))
                    break
                }
                val a = JSONArray(ri.body)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val os = o.optString("operatingSystem")
                    val ver = o.optString("operatingSystemVersion")
                    val minimal = o.optString("displayName").contains("minimal", ignoreCase = true)
                    val k = "$os|$ver|$minimal"
                    if (!seen.containsKey(k)) {
                        seen[k] = Opt("$os $ver" + (if (minimal) " (minimal)" else ""), o.optString("id"), os)
                    }
                }
                page = ri.nextPage ?: break
            }
            val images = seen.values.sortedWith(
                compareBy<Opt>({ !it.extra.contains("Ubuntu") }, { it.extra }).thenByDescending { it.label }
            )

            // Sous-réseaux
            val rs = OciClient.listSubnets(cfg, key)
            val subnets = mutableListOf<Opt>()
            if (rs.code in 200..299) {
                val a = JSONArray(rs.body)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    var lbl = o.optString("displayName")
                    if (o.optBoolean("prohibitPublicIpOnVnic", false)) lbl += " [privé]"
                    val ad = o.optString("availabilityDomain", "")
                    if (ad.isNotEmpty() && ad != "null") lbl += " [$ad]"
                    subnets.add(Opt(lbl, o.optString("id")))
                }
            } else {
                AppLog.add("Sous-réseaux : erreur ${rs.code} : " + OciClient.explain(rs))
            }

            Store.saveOpts(this, "cache_ad", ads)
            Store.saveOpts(this, "cache_image", images)
            Store.saveOpts(this, "cache_subnet", subnets)
            ui {
                adSp.set(ads)
                imageSp.set(images)
                subnetSp.set(subnets)
            }
            AppLog.add("✅ Connecté : ${ads.size} domaine(s), ${images.size} système(s), ${subnets.size} sous-réseau(x)")
            if (subnets.isEmpty()) {
                AppLog.add("Aucun sous-réseau : dans la console Oracle, lance l'assistant « Créer un VCN avec connectivité Internet », puis reconnecte-toi.")
            }
        }
    }

    private fun startSearch() {
        save()
        val cfg = Store.config(this)
        val miss = Store.missingForLaunch(cfg)
        if (miss.isNotEmpty()) {
            AppLog.add("Il manque : " + miss.joinToString(", "))
            return
        }
        val i = Intent(this, GrabService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun sshCommand(): String {
        val ip = Store.get(this, "last_ip")
        if (ip.isEmpty()) return ""
        val user = Store.get(this, "last_user", "ubuntu")
        return "ssh -i oci_ssh_key.pem $user@$ip"
    }

    private fun refreshIp() {
        val id = Store.get(this, "last_instance")
        if (id.isEmpty()) {
            AppLog.add("Aucune machine créée pour l'instant.")
            return
        }
        val cfg = Store.config(this)
        bg("Recherche de l'IP…") {
            val ip = OciClient.publicIp(cfg, KeyUtil.loadPrivate(cfg.privateKeyPem), id)
            if (ip != null) {
                Store.put(this, "last_ip", ip)
                AppLog.add("🌐 IP publique : $ip")
            } else {
                AppLog.add("Pas d'IP pour l'instant (la machine démarre encore ?).")
            }
        }
    }

    private fun adminComp() = ComponentName(this, AdminReceiver::class.java)
    private fun dpm() = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun refreshUi() {
        logView.text = AppLog.text()
        val cmd = sshCommand()
        infoView.text = (if (GrabState.running)
            "Recherche en cours · essai n°${GrabState.attempts} · ${GrabState.status}\n"
        else "Recherche arrêtée.\n") +
            (if (cmd.isEmpty()) "Aucune machine obtenue pour l'instant." else "IP : ${Store.get(this, "last_ip")}\n$cmd")
        adminView.text = "Administrateur appareil : " + (if (dpm().isAdminActive(adminComp())) "actif" else "inactif")
    }

    override fun onResume() {
        super.onResume()
        handler.post(object : Runnable {
            override fun run() {
                refreshUi()
                handler.postDelayed(this, 1000)
            }
        })
    }

    override fun onPause() {
        save()
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }
}
