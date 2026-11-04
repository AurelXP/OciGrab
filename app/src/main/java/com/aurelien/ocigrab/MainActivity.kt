package com.aurelien.ocigrab

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Palette et formes partagées par toute l'interface. */
object Ui {
    val BG = Color.parseColor("#0D1220")
    val SURFACE = Color.parseColor("#161F33")
    val FIELD = Color.parseColor("#1F2A44")
    val BORDER = Color.parseColor("#2C3A5A")
    val TEXT = Color.parseColor("#EEF2FA")
    val MUTED = Color.parseColor("#8E9DBA")
    val HINT = Color.parseColor("#5B6A88")
    val ACCENT = Color.parseColor("#FF6B4A")
    val OK = Color.parseColor("#34D399")
    val WARN = Color.parseColor("#FBBF24")
    val ERR = Color.parseColor("#F87171")

    fun rounded(ctx: Context, fill: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Int = 1): GradientDrawable {
        val d = ctx.resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * d
            if (stroke != null) setStroke(maxOf(1, (strokeDp * d).toInt()), stroke)
        }
    }
}

private class StyledAdapter(ctx: Context, items: List<String>) :
    ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, items) {

    private val d = ctx.resources.displayMetrics.density

    init {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        style(super.getView(position, convertView, parent), false)

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
        style(super.getDropDownView(position, convertView, parent), true)

    private fun style(v: View, drop: Boolean): View {
        (v as TextView).apply {
            setTextColor(Ui.TEXT)
            textSize = 14f
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
            val p = (12 * d).toInt()
            setPadding(p, p, p, p)
            if (drop) setBackgroundColor(Ui.FIELD)
        }
        return v
    }
}

/** Liste déroulante d'options mémorisée dans les préférences, avec fond arrondi et flèche. */
class OptSpinner(
    private val ctx: Context,
    private val prefKey: String,
    private val extraKey: String? = null,
    private val onChange: () -> Unit = {}
) {
    val view = Spinner(ctx)
    val box = FrameLayout(ctx)
    var opts: List<Opt> = emptyList()
        private set

    init {
        val d = ctx.resources.displayMetrics.density
        view.background = null
        view.setPopupBackgroundDrawable(Ui.rounded(ctx, Ui.FIELD, 12, Ui.BORDER))
        view.setPadding(0, 0, (28 * d).toInt(), 0)
        box.background = Ui.rounded(ctx, Ui.FIELD, 12, Ui.BORDER)
        box.addView(view, FrameLayout.LayoutParams(MATCH, WRAP))
        box.addView(TextView(ctx).apply {
            text = "▾"
            setTextColor(Ui.MUTED)
            textSize = 16f
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = (12 * d).toInt()
        })
        view.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                save()
                onChange()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    fun set(list: List<Opt>, preferId: String? = null, label: (Opt) -> String = { it.label }) {
        opts = list
        view.adapter = StyledAdapter(ctx, list.map(label))
        val want = preferId ?: Store.get(ctx, prefKey)
        val idx = list.indexOfFirst { it.id == want }
        view.setSelection(if (idx >= 0) idx else 0)
    }

    fun selected(): Opt? = opts.getOrNull(view.selectedItemPosition)

    fun save() {
        selected()?.let {
            Store.put(ctx, prefKey, it.id)
            if (extraKey != null) Store.put(ctx, extraKey, it.extra)
        }
    }
}

private enum class Kind { PRIMARY, SECONDARY, DANGER }

class MainActivity : Activity() {

    private val REQ_EXPORT = 42
    private val REQ_IMPORT = 43
    private val LAST = 5
    private val stepNames = listOf("Système", "Compte", "Machine", "SSH", "Protection", "Recherche")
    private val stepTitles = listOf(
        "Quel système veux-tu ?",
        "Connecte ton compte Oracle",
        "Choisis ta machine",
        "Prépare l'accès SSH",
        "Autorise l'arrière-plan",
        "Lance la recherche"
    )
    private val stepDescs = listOf(
        "Le système d'exploitation qui sera installé sur ta machine.",
        "On vérifie que tes identifiants marchent avant d'aller plus loin.",
        "Emplacement, version du système, puissance et stockage.",
        "La clé SSH sert à te connecter à la machine une fois créée.",
        "Pour que la recherche continue écran éteint.",
        "L'appli redemande une machine à Oracle jusqu'à en obtenir une."
    )
    private val osChoices = listOf(
        Triple("Canonical Ubuntu", "Ubuntu", "Le plus courant et le mieux documenté. Connexion SSH avec l'utilisateur « ubuntu »."),
        Triple("Oracle Linux", "Oracle Linux", "Conçu par Oracle, compatible RHEL / CentOS. Connexion SSH avec l'utilisateur « opc ».")
    )

    private val handler = Handler(Looper.getMainLooper())
    private val edits = mutableMapOf<String, EditText>()
    private var ready = false
    private var current = 0
    @Volatile private var connecting = false
    @Volatile private var connError = ""
    private var pendingContent = ""
    private var pendingName = ""
    private var preset = 0

    private lateinit var stepViews: List<LinearLayout>
    private val circles = ArrayList<TextView>()
    private val stepLabels = ArrayList<TextView>()
    private val leftHalves = ArrayList<View>()
    private val rightHalves = ArrayList<View>()
    private lateinit var scroll: ScrollView
    private lateinit var hintView: TextView
    private lateinit var btnBack: Button
    private lateinit var btnNext: Button

    private lateinit var apiPubView: TextView
    private lateinit var connBadge: TextView
    private lateinit var connInfo: TextView
    private lateinit var adSp: OptSpinner
    private lateinit var imageSp: OptSpinner
    private lateinit var subnetSp: OptSpinner
    private lateinit var osChips: LinearLayout
    private lateinit var osUserView: TextView
    private lateinit var osEmpty: TextView
    private val presetTiles = ArrayList<TextView>()
    private lateinit var ackBox: CheckBox
    private lateinit var batteryView: TextView
    private lateinit var adminView: TextView
    private lateinit var summaryView: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var ipView: TextView
    private lateinit var logView: TextView

    private var allImages: List<Opt> = emptyList()
    private var osFamily = ""
    private lateinit var osBox: LinearLayout

    // gestion des machines
    private lateinit var managerPage: LinearLayout
    private lateinit var managerList: LinearLayout
    private lateinit var managerStatus: TextView
    private var managerOpen = false
    @Volatile private var loadingMachines = false
    private var machines: List<Machine> = emptyList()
    private var tick = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun ui(block: () -> Unit) = runOnUiThread { block() }

    // N'écrit que si le texte change : sinon la sélection (copier l'IP, le journal) saute chaque seconde.
    private var TextView.value: String
        get() = text.toString()
        set(v) { if (text.toString() != v) text = v }
    private fun txt(k: String) = edits[k]?.text?.toString()?.trim() ?: ""

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

    // ---------- briques d'interface ----------

    private fun tv(text: String, size: Float, color: Int = Ui.TEXT, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun enable(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.4f
    }

    private fun stepContainer(i: Int): LinearLayout {
        val s = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        s.addView(tv(stepTitles[i], 22f, Ui.TEXT, true))
        s.addView(tv(stepDescs[i], 13.5f, Ui.MUTED).apply { setPadding(0, dp(4), 0, dp(16)) })
        return s
    }

    private fun card(parent: LinearLayout, title: String? = null, sub: String? = null): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(this@MainActivity, Ui.SURFACE, 18, Ui.BORDER)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        parent.addView(c, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(14) })
        if (title != null) c.addView(tv(title, 16f, Ui.TEXT, true))
        if (sub != null) c.addView(tv(sub, 12.5f, Ui.MUTED).apply { setPadding(0, dp(4), 0, dp(6)) })
        return c
    }

    private fun caption(parent: LinearLayout, t: String) {
        parent.addView(tv(t, 12f, Ui.MUTED, true).apply { setPadding(dp(2), dp(12), 0, dp(6)) })
    }

    private fun field(
        parent: LinearLayout, key: String, label: String, hint: String,
        def: String = "", multiline: Boolean = false, mono: Boolean = false, numeric: Boolean = false
    ): EditText {
        caption(parent, label)
        val e = EditText(this).apply {
            this.hint = hint
            setHintTextColor(Ui.HINT)
            setTextColor(Ui.TEXT)
            setText(Store.get(this@MainActivity, key, def))
            textSize = 14f
            background = Ui.rounded(this@MainActivity, Ui.FIELD, 12, Ui.BORDER)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            if (mono) typeface = Typeface.MONOSPACE
            if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                minLines = 2
                maxLines = 5
                gravity = Gravity.TOP
            } else {
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine(true)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (ready) updateNav()
                }
            })
        }
        edits[key] = e
        parent.addView(e, LinearLayout.LayoutParams(MATCH, WRAP))
        return e
    }

    private fun row(parent: LinearLayout): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        parent.addView(r, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        return r
    }

    private fun btn(parent: LinearLayout, label: String, kind: Kind = Kind.SECONDARY, onClick: () -> Unit): Button {
        val fill = when (kind) {
            Kind.PRIMARY -> Ui.ACCENT
            Kind.DANGER -> Color.parseColor("#3A1F2B")
            Kind.SECONDARY -> Ui.FIELD
        }
        val stroke = when (kind) {
            Kind.PRIMARY -> null
            Kind.DANGER -> Ui.ERR
            Kind.SECONDARY -> Ui.BORDER
        }
        val b = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (kind == Kind.DANGER) Ui.ERR else Color.WHITE)
            minHeight = dp(48)
            minimumHeight = dp(48)
            stateListAnimator = null
            background = RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
                Ui.rounded(this@MainActivity, fill, 12, stroke), null
            )
            setOnClickListener { onClick() }
        }
        val lp = if (parent.orientation == LinearLayout.HORIZONTAL)
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
        else
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        parent.addView(b, lp)
        return b
    }

    private fun mono(t: String, size: Float = 11f): TextView =
        TextView(this).apply {
            text = t
            textSize = size
            typeface = Typeface.MONOSPACE
            setTextColor(Ui.TEXT)
            setTextIsSelectable(true)
            background = Ui.rounded(this@MainActivity, Ui.BG, 10, Ui.BORDER)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

    /** Lit « user=… tenancy=… fingerprint=… region=… » (aperçu du fichier de config Oracle) dans le presse-papiers. */
    private fun pasteOracleConfig() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        val found = mutableListOf<String>()
        for (k in listOf("tenancy", "user", "fingerprint", "region")) {
            val m = Regex("(?:^|\\s)$k\\s*=\\s*(\\S+)").find(t) ?: continue
            edits[k]?.setText(m.groupValues[1])
            found.add(k)
        }
        val msg = if (found.isEmpty())
            "Rien de reconnu : copie d'abord l'aperçu du fichier de configuration dans la console Oracle."
        else
            "Rempli : " + found.joinToString(", ") + ". Ajoute maintenant la clé privée."
        AppLog.add(msg)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun importPem() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        startActivityForResult(i, REQ_IMPORT)
    }

    private fun copy(text: String) {
        if (text.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("oci", text))
        AppLog.add("Copié dans le presse-papiers.")
        Toast.makeText(this, "Copié", Toast.LENGTH_SHORT).show()
    }

    private fun export(name: String, content: String) {
        if (content.isBlank()) {
            AppLog.add("Rien à télécharger : génère ou saisis d'abord la clé.")
            Toast.makeText(this, "Génère ou saisis d'abord la clé", Toast.LENGTH_SHORT).show()
            return
        }
        pendingContent = content
        pendingName = name
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_TITLE, name)
        startActivityForResult(i, REQ_EXPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { r ->
                    val buf = CharArray(20000)
                    val n = r.read(buf)
                    String(buf, 0, maxOf(n, 0))
                } ?: ""
                if (!text.contains("PRIVATE KEY")) {
                    Toast.makeText(this, "Ce fichier ne contient pas de clé privée", Toast.LENGTH_LONG).show()
                    return
                }
                val pub = KeyUtil.publicPemOfPrivatePem(text)
                val fp = KeyUtil.fingerprintOfPrivatePem(text)
                edits["key"]?.setText(text.trim())
                edits["fingerprint"]?.setText(fp)
                apiPubView.text = pub
                Store.put(this, "api_pub", pub)
                AppLog.add("Clé privée importée. Empreinte : $fp")
                Toast.makeText(this, "Clé importée. Empreinte : $fp", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                val msg = "Clé illisible : ${e.message ?: e.javaClass.simpleName}"
                AppLog.add(msg)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
            return
        }
        if (requestCode == REQ_EXPORT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(pendingContent.toByteArray(Charsets.UTF_8)) }
                AppLog.add("Fichier enregistré.")
                if (pendingName == "oci_ssh_key.pem") {
                    Store.put(this, "ssh_ack", txt("ssh"))
                }
            } catch (e: Exception) {
                AppLog.add("Échec de l'enregistrement : ${e.message}")
            }
            pendingContent = ""
            pendingName = ""
            if (ready) updateNav()
        }
    }

    // ---------- écran ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        osFamily = Store.get(this, "os_family")

        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(16), dp(6))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv("Oracle Cloud Grabber and Manager", 19f, Ui.TEXT, true))
                addView(tv("Machine ARM Always Free sur Oracle Cloud", 12.5f, Ui.MUTED))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(pill("🖥 Machines") { openManager() })
        })
        page.addView(buildStepper())

        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        stepViews = listOf(buildOsStep(), buildAccount(), buildMachine(), buildSsh(), buildProtection(), buildSearch())
        stepViews.forEach { holder.addView(it) }
        scroll.addView(holder)
        page.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // barre du bas : message de validation + navigation
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.SURFACE)
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        hintView = tv("", 12.5f, Ui.WARN).apply { setPadding(dp(4), 0, dp(4), dp(8)) }
        bottom.addView(hintView)
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(nav, LinearLayout.LayoutParams(MATCH, WRAP))
        btnBack = btn(nav, "‹ Retour", Kind.SECONDARY) { goTo(current - 1) }
        btnNext = btn(nav, "Continuer ›", Kind.PRIMARY) { next() }
        (btnNext.layoutParams as LinearLayout.LayoutParams).weight = 2f
        page.addView(bottom, LinearLayout.LayoutParams(MATCH, WRAP))

        managerPage = buildManager()
        setContentView(FrameLayout(this).apply {
            addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(managerPage, FrameLayout.LayoutParams(MATCH, MATCH))
        })

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        ready = true
        show(blockedIndex())
    }

    private fun buildStepper(): LinearLayout {
        val rowView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(10), dp(8), dp(12))
        }
        for (i in stepNames.indices) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                contentDescription = "Étape ${i + 1} : ${stepNames[i]}"
                setOnClickListener { goTo(i) }
            }
            val top = FrameLayout(this)
            val halves = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val l = View(this).apply { if (i == 0) visibility = View.INVISIBLE }
            val r = View(this).apply { if (i == LAST) visibility = View.INVISIBLE }
            halves.addView(l, LinearLayout.LayoutParams(0, dp(3), 1f))
            halves.addView(r, LinearLayout.LayoutParams(0, dp(3), 1f))
            top.addView(halves, FrameLayout.LayoutParams(MATCH, dp(3), Gravity.CENTER_VERTICAL))
            val c = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            }
            top.addView(c, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
            cell.addView(top, LinearLayout.LayoutParams(MATCH, dp(32)))
            val lab = tv(stepNames[i], 10f, Ui.MUTED).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, dp(5), 0, 0)
            }
            cell.addView(lab, LinearLayout.LayoutParams(MATCH, WRAP))
            rowView.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
            circles.add(c)
            stepLabels.add(lab)
            leftHalves.add(l)
            rightHalves.add(r)
        }
        return rowView
    }

    // ----- étape 1 : compte -----

    private fun buildAccount(): LinearLayout {
        val s = stepContainer(1)

        val c1 = card(
            s, "1 · Génère ta clé API",
            "Ajoute ensuite la clé publique dans la console Oracle : Profil › Clés API › Ajouter une clé API › Coller une clé publique."
        )
        btn(c1, "Générer une clé API", Kind.PRIMARY) { genApiKey() }
        apiPubView = mono(Store.get(this, "api_pub"), 10f)
        c1.addView(apiPubView, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        val r1 = row(c1)
        btn(r1, "Copier") { copy(apiPubView.text.toString()) }
        btn(r1, "Télécharger") { export("oci_api_public.pem", apiPubView.text.toString()) }

        val c2 = card(s, "2 · Colle tes identifiants", "Tu les trouves dans la console Oracle (profil et locataire).")
        btn(c2, "📋 Coller la config Oracle (presse-papiers)") { pasteOracleConfig() }
        field(c2, "tenancy", "Tenancy OCID", "ocid1.tenancy.oc1..…", mono = true)
        field(c2, "user", "User OCID", "ocid1.user.oc1..…", mono = true)
        field(c2, "region", "Région", "eu-paris-1")
        field(c2, "fingerprint", "Empreinte de la clé API", "Remplie automatiquement", mono = true)
        field(c2, "key", "Clé privée API (PEM)", "-----BEGIN PRIVATE KEY-----", multiline = true, mono = true)
        btn(c2, "📂 Importer un fichier de clé privée (.pem)") { importPem() }
        btn(c2, "Sauvegarder la clé privée") { export("oci_api_private.pem", txt("key")) }
        field(c2, "compartment", "Compartiment (optionnel)", "Vide = racine du locataire", mono = true)

        val c3 = card(s, "3 · Vérifie la connexion", "Il faut une connexion réussie pour continuer.")
        connBadge = tv("", 14f, Ui.MUTED, true)
        c3.addView(connBadge)
        connInfo = tv("", 12.5f, Ui.MUTED).apply { setPadding(0, dp(2), 0, 0) }
        c3.addView(connInfo)
        btn(c3, "🔑 Se connecter à Oracle", Kind.PRIMARY) { connect() }
        return s
    }

    // ----- étape 2 : machine -----

    private fun buildMachine(): LinearLayout {
        val s = stepContainer(2)

        val c1 = card(s, "Emplacement")
        caption(c1, "Domaine de disponibilité")
        adSp = OptSpinner(this, "ad") { if (ready) updateNav() }
        c1.addView(adSp.box, LinearLayout.LayoutParams(MATCH, WRAP))
        caption(c1, "Sous-réseau")
        subnetSp = OptSpinner(this, "subnet") { if (ready) updateNav() }
        c1.addView(subnetSp.box, LinearLayout.LayoutParams(MATCH, WRAP))

        val c2 = card(s, "Système d'exploitation", "Choisis la distribution, puis sa version.")
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        osChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hs.addView(osChips)
        c2.addView(hs, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        osEmpty = tv("Aucun système chargé : reviens à l'étape Compte et connecte-toi.", 12.5f, Ui.WARN)
        c2.addView(osEmpty)
        caption(c2, "Version")
        imageSp = OptSpinner(this, "image", "image_os") { if (ready) updateNav() }
        c2.addView(imageSp.box, LinearLayout.LayoutParams(MATCH, WRAP))
        osUserView = tv("", 12f, Ui.MUTED).apply { setPadding(dp(2), dp(8), 0, 0) }
        c2.addView(osUserView)

        val c3 = card(s, "Puissance", "Quota Always Free : 4 cœurs et 24 Go au total.")
        preset = (Store.get(this, "preset", "1").toIntOrNull() ?: 1).coerceIn(0, 3)
        val pr = row(c3)
        for (i in 0..3) {
            val t = TextView(this).apply {
                gravity = Gravity.CENTER
                text = "${i + 1} cœur" + (if (i > 0) "s" else "") + "\n${6 * (i + 1)} Go"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener {
                    preset = i
                    Store.put(this@MainActivity, "preset", i.toString())
                    renderPresets()
                }
            }
            presetTiles.add(t)
            pr.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        renderPresets()

        val c4 = card(s, "Stockage et nom")
        field(c4, "boot", "Disque de démarrage (Go)", "47 à 200", "50", numeric = true)
        field(c4, "name", "Nom de l'instance", "arm-free", "arm-free")
        field(c4, "interval", "Délai entre deux tentatives (secondes)", "10 minimum", "60", numeric = true)

        adSp.set(Store.loadOpts(this, "cache_ad"))
        subnetSp.set(Store.loadOpts(this, "cache_subnet"))
        setImages(Store.loadOpts(this, "cache_image"))
        return s
    }

    private fun renderPresets() {
        for ((i, t) in presetTiles.withIndex()) {
            val sel = i == preset
            t.setTextColor(if (sel) Ui.ACCENT else Ui.TEXT)
            t.background = if (sel)
                Ui.rounded(this, Color.parseColor("#2A2434"), 12, Ui.ACCENT, 2)
            else
                Ui.rounded(this, Ui.FIELD, 12, Ui.BORDER)
        }
    }

    private fun osName(raw: String) = raw.removePrefix("Canonical ").trim()

    private fun setImages(list: List<Opt>) {
        allImages = list
        val fams = list.map { it.extra }.distinct()
        val saved = Store.get(this, "os_family")
        osFamily = when {
            saved in fams -> saved
            else -> fams.firstOrNull { it.contains("Ubuntu", ignoreCase = true) } ?: fams.firstOrNull() ?: osFamily
        }
        if (fams.isNotEmpty()) Store.put(this, "os_family", osFamily)
        renderOs()
        renderOsTiles()
    }

    private fun renderOs() {
        osChips.removeAllViews()
        val fams = allImages.map { it.extra }.distinct()
        for (f in fams) {
            val sel = f == osFamily
            val n = allImages.count { it.extra == f }
            val chip = TextView(this).apply {
                text = osName(f) + "  ·  $n"
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (sel) Color.WHITE else Ui.TEXT)
                background = if (sel)
                    Ui.rounded(this@MainActivity, Ui.ACCENT, 20)
                else
                    Ui.rounded(this@MainActivity, Ui.FIELD, 20, Ui.BORDER)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setOnClickListener { chooseOs(f) }
            }
            osChips.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
        osEmpty.visibility = if (fams.isEmpty()) View.VISIBLE else View.GONE
        val versions = allImages.filter { it.extra == osFamily }
        imageSp.set(versions) { it.label.removePrefix(it.extra).trim().ifEmpty { it.label } }
        osUserView.text = if (osFamily.isEmpty()) "" else
            "Utilisateur SSH sur cette machine : " + (if (osFamily.contains("Ubuntu", ignoreCase = true)) "ubuntu" else "opc")
    }

    // ----- étape 3 : SSH -----

    private fun buildSsh(): LinearLayout {
        val s = stepContainer(3)
        val c1 = card(
            s, "Clé SSH",
            "Génère une paire de clés (ou colle ta clé publique). Sans la clé privée, impossible de te connecter."
        )
        btn(c1, "Générer une paire de clés SSH", Kind.PRIMARY) { genSsh() }
        field(c1, "ssh", "Clé publique SSH", "ssh-rsa AAAA… ou ssh-ed25519 AAAA…", multiline = true, mono = true)
        val r = row(c1)
        btn(r, "Clé privée") { export("oci_ssh_key.pem", Store.get(this, "ssh_priv")) }
        btn(r, "Clé publique") { export("oci_ssh_key.pub", txt("ssh")) }

        val c2 = card(s, "Sauvegarde")
        ackBox = CheckBox(this).apply {
            text = "J'ai sauvegardé la clé privée SSH"
            setTextColor(Ui.TEXT)
            textSize = 14f
            buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
            setOnCheckedChangeListener { _, checked ->
                Store.put(this@MainActivity, "ssh_ack", if (checked) txt("ssh") else "")
                if (ready) updateNav()
            }
        }
        c2.addView(ackBox)
        c2.addView(tv("Le téléchargement de la clé privée coche automatiquement cette case.", 12f, Ui.MUTED))
        return s
    }

    // ----- étape 4 : protection -----

    private fun buildProtection(): LinearLayout {
        val s = stepContainer(4)
        val c1 = card(s, "Arrière-plan (obligatoire)", "Sans cette autorisation, Android peut endormir l'appli et stopper la recherche.")
        batteryView = tv("", 14f, Ui.MUTED, true)
        c1.addView(batteryView)
        btn(c1, "Autoriser en arrière-plan", Kind.PRIMARY) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }

        val c2 = card(s, "Administrateur appareil (conseillé)", "Empêche la désinstallation directe de l'appli pendant la recherche.")
        adminView = tv("", 14f, Ui.MUTED, true)
        c2.addView(adminView)
        val r = row(c2)
        btn(r, "Activer") {
            val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComp())
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Empêche la désinstallation de l'appli sans désactiver d'abord l'administrateur."
                )
            startActivity(i)
        }
        btn(r, "Désactiver", Kind.DANGER) { dpm().removeActiveAdmin(adminComp()) }
        return s
    }

    // ----- étape 5 : recherche -----

    private fun buildSearch(): LinearLayout {
        val s = stepContainer(5)

        val c0 = card(s, "Récapitulatif")
        summaryView = tv("", 13.5f, Ui.TEXT)
        c0.addView(summaryView)

        val c1 = card(s, "Recherche")
        statusTitle = tv("", 16f, Ui.MUTED, true)
        statusDetail = tv("", 13f, Ui.MUTED).apply { setPadding(0, dp(2), 0, dp(4)) }
        c1.addView(statusTitle)
        c1.addView(statusDetail)
        val r = row(c1)
        btnStart = btn(r, "▶ Démarrer", Kind.PRIMARY) { startSearch() }
        btnStop = btn(r, "■ Arrêter", Kind.DANGER) {
            startService(Intent(this, GrabService::class.java).setAction("STOP"))
        }

        val c2 = card(s, "Machine obtenue")
        ipView = mono("", 12.5f)
        c2.addView(ipView)
        val r2 = row(c2)
        btn(r2, "Rafraîchir l'IP") { refreshIp() }
        btn(r2, "Copier SSH") { copy(sshCommand()) }
        btn(c2, "🖥 Gérer mes machines", Kind.PRIMARY) { openManager() }

        val c3 = card(s, "Journal")
        logView = tv("", 11f, Ui.MUTED).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        c3.addView(logView)
        return s
    }

    // ----- étape 1 : système d'exploitation -----

    private fun buildOsStep(): LinearLayout {
        val s = stepContainer(0)
        val c = card(
            s, "Système d'exploitation",
            "Tu pourras choisir la version à l'étape Machine. D'autres systèmes peuvent apparaître après la connexion."
        )
        osBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(osBox)
        renderOsTiles()
        return s
    }

    private fun renderOsTiles() {
        if (!this::osBox.isInitialized) return
        osBox.removeAllViews()
        val list = osChoices.toMutableList()
        if (osFamily.isNotEmpty() && list.none { it.first == osFamily }) {
            list.add(Triple(osFamily, osName(osFamily), "Choisi à l'étape Machine."))
        }
        for ((fam, title, desc) in list) {
            val sel = fam == osFamily
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = if (sel)
                    Ui.rounded(this@MainActivity, Color.parseColor("#2A2434"), 14, Ui.ACCENT, 2)
                else
                    Ui.rounded(this@MainActivity, Ui.FIELD, 14, Ui.BORDER)
                setOnClickListener { chooseOs(fam) }
            }
            t.addView(tv((if (sel) "● " else "○ ") + title, 16f, if (sel) Ui.ACCENT else Ui.TEXT, true))
            t.addView(tv(desc, 12.5f, Ui.MUTED).apply { setPadding(0, dp(4), 0, 0) })
            osBox.addView(t, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        }
    }

    private fun chooseOs(fam: String) {
        osFamily = fam
        Store.put(this, "os_family", fam)
        renderOsTiles()
        if (this::osChips.isInitialized) renderOs()
        if (ready) updateNav()
    }

    // ---------- gestion des machines ----------

    private fun pill(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            gravity = Gravity.CENTER
            background = Ui.rounded(this@MainActivity, Ui.FIELD, 20, Ui.BORDER)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setOnClickListener { onClick() }
        }

    private fun buildManager(): LinearLayout {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            visibility = View.GONE
            isClickable = true
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(18), dp(14), dp(8))
        }
        head.addView(pill("‹ Retour") { closeManager() })
        head.addView(
            tv("Mes machines", 20f, Ui.TEXT, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        head.addView(pill("↻ Actualiser") { loadMachines() })
        p.addView(head)
        managerStatus = tv("", 12.5f, Ui.MUTED).apply { setPadding(dp(20), 0, dp(20), dp(8)) }
        p.addView(managerStatus)
        val sc = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        managerList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        sc.addView(managerList)
        p.addView(sc, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return p
    }

    private fun openManager() {
        if (Store.get(this, "connected_sig") != currentSig() || txt("key").isEmpty()) {
            Toast.makeText(this, "Connecte-toi d'abord à Oracle (étape Compte).", Toast.LENGTH_LONG).show()
            return
        }
        save()
        managerOpen = true
        managerPage.visibility = View.VISIBLE
        loadMachines()
    }

    private fun closeManager() {
        managerOpen = false
        managerPage.visibility = View.GONE
    }

    private fun loadMachines() {
        if (loadingMachines) return
        save()
        val cfg = Store.config(this)
        loadingMachines = true
        managerStatus.value = "Chargement des machines…"
        Thread {
            try {
                val key = KeyUtil.loadPrivate(cfg.privateKeyPem)
                val out = ArrayList<Machine>()
                var page: String? = null
                for (n in 0 until 5) {
                    val r = OciClient.listInstances(cfg, key, page)
                    if (r.code !in 200..299) {
                        val msg = "Erreur ${r.code} : " + OciClient.explain(r)
                        ui { managerStatus.value = msg }
                        return@Thread
                    }
                    val a = JSONArray(r.body)
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i)
                        val st = o.optString("lifecycleState")
                        if (st == "TERMINATED") continue
                        val sc = o.optJSONObject("shapeConfig")
                        out.add(
                            Machine(
                                o.optString("id"), o.optString("displayName"), st, o.optString("shape"),
                                sc?.optDouble("ocpus", 0.0) ?: 0.0, sc?.optDouble("memoryInGBs", 0.0) ?: 0.0,
                                o.optString("timeCreated").take(10)
                            )
                        )
                    }
                    page = r.nextPage ?: break
                }
                for (m in out) {
                    if (m.state == "RUNNING") m.ip = OciClient.publicIp(cfg, key, m.id) ?: ""
                }
                ui {
                    machines = out
                    renderMachines()
                    val busy = out.any { it.state != "RUNNING" && it.state != "STOPPED" }
                    managerStatus.value = "${out.size} machine(s)" + (if (busy) " · mise à jour automatique en cours" else "") +
                        (if (cfg.compartment == cfg.tenancy) " · compartiment racine" else "")
                }
            } catch (e: Exception) {
                ui { managerStatus.value = "Erreur : ${e.message ?: e.javaClass.simpleName}" }
            } finally {
                loadingMachines = false
            }
        }.start()
    }

    private fun stateColor(s: String) = when (s) {
        "RUNNING" -> Ui.OK
        "STOPPED" -> Ui.MUTED
        else -> Ui.WARN
    }

    private fun stateLabel(s: String) = when (s) {
        "RUNNING" -> "En marche"
        "STOPPED" -> "Arrêtée"
        "STARTING" -> "Démarrage…"
        "STOPPING" -> "Arrêt…"
        "PROVISIONING" -> "Création…"
        "TERMINATING" -> "Suppression…"
        "MOVING" -> "Déplacement…"
        else -> s
    }

    private fun renderMachines() {
        managerList.removeAllViews()
        if (machines.isEmpty()) {
            managerList.addView(
                tv("Aucune machine dans ce compartiment.", 14f, Ui.MUTED).apply { setPadding(dp(4), dp(12), dp(4), 0) }
            )
            return
        }
        for (m in machines) machineCard(m)
    }

    private fun machineCard(m: Machine) {
        val c = card(managerList)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(
            tv(m.name, 16f, Ui.TEXT, true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        top.addView(tv("● " + stateLabel(m.state), 12.5f, stateColor(m.state), true))
        c.addView(top)
        val ram = if (m.memoryGb % 1.0 == 0.0) m.memoryGb.toInt().toString() else m.memoryGb.toString()
        c.addView(
            tv("${m.shape} · ${m.ocpus.toInt()} cœur(s) · $ram Go · créée le ${m.created}", 12.5f, Ui.MUTED)
                .apply { setPadding(0, dp(4), 0, dp(4)) }
        )
        if (m.ip.isNotEmpty()) {
            c.addView(mono("IP : ${m.ip}", 12f), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        }

        val transitional = m.state != "RUNNING" && m.state != "STOPPED"
        if (transitional) {
            c.addView(
                tv("Opération en cours : la liste se met à jour toute seule.", 12f, Ui.WARN)
                    .apply { setPadding(0, dp(8), 0, 0) }
            )
        } else {
            val r1 = row(c)
            if (m.state == "STOPPED") {
                btn(r1, "▶ Démarrer", Kind.PRIMARY) { machineAction(m, "START", "Démarrage") }
            } else {
                btn(r1, "■ Arrêter") {
                    confirmThen("Arrêter « ${m.name} » ?") { machineAction(m, "SOFTSTOP", "Arrêt") }
                }
                btn(r1, "↻ Redémarrer") {
                    confirmThen("Redémarrer « ${m.name} » ?") { machineAction(m, "SOFTRESET", "Redémarrage") }
                }
            }
        }
        val r2 = row(c)
        btn(r2, "✏ Renommer") { renameDialog(m) }
        if (m.shape.contains("Flex")) btn(r2, "⚙ Taille") { resizeDialog(m) }
        btn(r2, "🗑 Supprimer", Kind.DANGER) { deleteDialog(m) }
        if (m.ip.isNotEmpty()) {
            val r3 = row(c)
            btn(r3, "Copier l'IP") { copy(m.ip) }
            btn(r3, "Copier SSH") {
                copy("ssh -i oci_ssh_key.pem ${Store.get(this, "last_user", "ubuntu")}@${m.ip}")
            }
        }
    }

    private fun machineAction(m: Machine, action: String, label: String) {
        runManager("$label de « ${m.name} »") { cfg, key -> OciClient.instanceAction(cfg, key, m.id, action) }
    }

    private fun runManager(label: String, call: (OciConfig, java.security.PrivateKey) -> OciResult) {
        val cfg = Store.config(this)
        managerStatus.value = "$label…"
        AppLog.add("Gestion : $label…")
        Thread {
            try {
                val key = KeyUtil.loadPrivate(cfg.privateKeyPem)
                val r = call(cfg, key)
                if (r.code in 200..299) {
                    AppLog.add("Gestion : $label : demandé à Oracle.")
                    ui { managerStatus.value = "$label : demandé à Oracle." }
                } else {
                    val msg = "$label refusé (${r.code}) : " + OciClient.explain(r)
                    AppLog.add("⛔ $msg")
                    ui {
                        managerStatus.value = msg
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                val msg = "$label : erreur ${e.message ?: e.javaClass.simpleName}"
                AppLog.add("⛔ $msg")
                ui { managerStatus.value = msg }
            }
            handler.postDelayed({ loadMachines() }, 2500)
        }.start()
    }

    private val dialogTheme get() = android.R.style.Theme_DeviceDefault_Dialog_Alert

    private fun confirmThen(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this, dialogTheme)
            .setMessage(msg)
            .setPositiveButton("Confirmer") { _, _ -> ok() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun dialogField(hint: String, value: String, numeric: Boolean): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            setHintTextColor(Ui.HINT)
            setTextColor(Ui.TEXT)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
        }

    private fun dialogBox(vararg views: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            for (v in views) addView(v)
        }

    private fun renameDialog(m: Machine) {
        val et = dialogField("Nouveau nom", m.name, false)
        AlertDialog.Builder(this, dialogTheme)
            .setTitle("Renommer la machine")
            .setView(dialogBox(et))
            .setPositiveButton("Enregistrer") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty() && n != m.name) {
                    runManager("Renommage de « ${m.name} »") { cfg, key ->
                        OciClient.updateInstance(cfg, key, m.id, n, null, null)
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun resizeDialog(m: Machine) {
        val cores = dialogField("Cœurs (1 à 4)", m.ocpus.toInt().toString(), true)
        val ram = dialogField("Mémoire en Go (1 à 24)", m.memoryGb.toInt().toString(), true)
        val note = tv(
            "La machine redémarre pour appliquer le changement. Quota Always Free : 4 cœurs et 24 Go au total.",
            12f, Ui.MUTED
        ).apply { setPadding(0, dp(10), 0, 0) }
        AlertDialog.Builder(this, dialogTheme)
            .setTitle("Changer la taille")
            .setView(dialogBox(cores, ram, note))
            .setPositiveButton("Appliquer") { _, _ ->
                val c = cores.text.toString().toIntOrNull()
                val g = ram.text.toString().toIntOrNull()
                if (c == null || c !in 1..4 || g == null || g !in 1..24) {
                    Toast.makeText(this, "Cœurs : 1 à 4, mémoire : 1 à 24 Go.", Toast.LENGTH_LONG).show()
                } else {
                    runManager("Changement de taille de « ${m.name} »") { cfg, key ->
                        OciClient.updateInstance(cfg, key, m.id, null, c, g)
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun deleteDialog(m: Machine) {
        val et = dialogField("Tape le nom exact pour confirmer", "", false)
        val d = AlertDialog.Builder(this, dialogTheme)
            .setTitle("Supprimer définitivement ?")
            .setMessage("« ${m.name} » et son disque de démarrage seront détruits. Action irréversible.")
            .setView(dialogBox(et))
            .setPositiveButton("Supprimer") { _, _ ->
                if (m.id == Store.get(this, "last_instance")) {
                    Store.put(this, "last_instance", "")
                    Store.put(this, "last_ip", "")
                }
                runManager("Suppression de « ${m.name} »") { cfg, key -> OciClient.terminateInstance(cfg, key, m.id) }
            }
            .setNegativeButton("Annuler", null)
            .create()
        d.show()
        val ok = d.getButton(AlertDialog.BUTTON_POSITIVE)
        ok.isEnabled = false
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                ok.isEnabled = et.text.toString().trim() == m.name
            }
        })
    }

    // ---------- validation et navigation ----------

    private fun currentSig(): String =
        "${txt("tenancy")}|${txt("user")}|${txt("region")}|${txt("compartment")}|${txt("key").hashCode()}"

    private fun batteryOk(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun issues(i: Int): List<String> {
        val m = mutableListOf<String>()
        when (i) {
            0 -> {
                if (osFamily.isEmpty()) m.add("choisis un système d'exploitation")
            }
            1 -> {
                val t = txt("tenancy")
                val u = txt("user")
                val r = txt("region")
                val c = txt("compartment")
                if (t.isEmpty()) m.add("Tenancy OCID")
                else if (!t.startsWith("ocid1.tenancy")) m.add("Tenancy OCID invalide (commence par ocid1.tenancy)")
                if (u.isEmpty()) m.add("User OCID")
                else if (!u.startsWith("ocid1.user")) m.add("User OCID invalide (commence par ocid1.user)")
                if (r.isEmpty()) m.add("Région")
                else if (!Regex("[a-z]{2}-[a-z0-9-]+-\\d+").matches(r)) m.add("Région invalide (ex : eu-paris-1)")
                if (txt("key").isEmpty()) m.add("Clé privée API")
                if (c.isNotEmpty() && !c.startsWith("ocid1.compartment") && !c.startsWith("ocid1.tenancy"))
                    m.add("Compartiment invalide")
                if (m.isEmpty() && Store.get(this, "connected_sig") != currentSig())
                    m.add("clique sur « Se connecter à Oracle » et attends le ✅")
            }
            2 -> {
                if (adSp.selected() == null) m.add("Domaine de disponibilité")
                if (subnetSp.selected() == null) m.add("Sous-réseau")
                if (imageSp.selected() == null) m.add("Système d'exploitation")
                val boot = txt("boot").toIntOrNull()
                if (boot == null || boot < 47) m.add("Disque : 47 Go minimum")
                else if (boot > 200) m.add("Disque : 200 Go maximum (quota Always Free)")
                if (txt("name").isEmpty()) m.add("Nom de l'instance")
                val iv = txt("interval").toIntOrNull()
                if (iv == null || iv < 10) m.add("Délai : 10 secondes minimum")
            }
            3 -> {
                val k = txt("ssh")
                if (k.isEmpty()) m.add("Clé publique SSH (génère-la ou colle la tienne)")
                else if (!k.startsWith("ssh-") && !k.startsWith("ecdsa-"))
                    m.add("Clé publique SSH invalide (elle commence par ssh-rsa, ssh-ed25519…)")
                else if (k == Store.get(this, "ssh_gen_pub") && Store.get(this, "ssh_ack") != k)
                    m.add("confirme avoir sauvegardé la clé privée SSH")
            }
            4 -> {
                if (!batteryOk()) m.add("autoriser l'appli en arrière-plan (batterie)")
            }
        }
        return m
    }

    private fun blockedIndex(): Int {
        for (i in 0 until LAST) if (issues(i).isNotEmpty()) return i
        return LAST
    }

    private fun show(i: Int) {
        current = i.coerceIn(0, LAST)
        stepViews.forEachIndexed { idx, v -> v.visibility = if (idx == current) View.VISIBLE else View.GONE }
        scroll.post { scroll.scrollTo(0, 0) }
        updateNav()
    }

    private fun goTo(target: Int) {
        if (target < 0 || target > LAST || target == current) return
        if (target > current) {
            val b = blockedIndex()
            if (b < target) {
                val why = issues(b).firstOrNull() ?: ""
                Toast.makeText(this, "Termine d'abord « ${stepNames[b]} » : $why", Toast.LENGTH_LONG).show()
                return
            }
        }
        save()
        show(target)
    }

    private fun next() {
        if (current < LAST && issues(current).isEmpty()) {
            save()
            show(current + 1)
        }
    }

    private fun updateNav() {
        if (!ready) return
        val iss = (0 until LAST).map { issues(it) }
        val blocked = iss.indexOfFirst { it.isNotEmpty() }.let { if (it < 0) LAST else it }
        updateStepper(iss, blocked)

        val cur = if (current < LAST) iss[current] else emptyList()
        if (current == LAST) {
            hintView.visibility = View.GONE
        } else {
            hintView.visibility = View.VISIBLE
            if (cur.isEmpty()) {
                hintView.setTextColor(Ui.OK)
                hintView.text = "✓ Étape validée, tu peux continuer."
            } else {
                hintView.setTextColor(Ui.WARN)
                hintView.text = "À faire : " + cur.joinToString(" · ")
            }
        }
        btnNext.visibility = if (current == LAST) View.GONE else View.VISIBLE
        btnBack.visibility = if (current == 0) View.INVISIBLE else View.VISIBLE
        enable(btnNext, cur.isEmpty())
    }

    private fun updateStepper(iss: List<List<String>>, blocked: Int) {
        for (i in 0..LAST) {
            val c = circles[i]
            val valid = i < LAST && iss[i].isEmpty()
            when {
                i == current -> {
                    c.text = (i + 1).toString()
                    c.setTextColor(Color.WHITE)
                    c.background = Ui.rounded(this, Ui.ACCENT, 16)
                }
                valid -> {
                    c.text = "✓"
                    c.setTextColor(Ui.BG)
                    c.background = Ui.rounded(this, Ui.OK, 16)
                }
                i <= blocked -> {
                    c.text = (i + 1).toString()
                    c.setTextColor(Ui.ACCENT)
                    c.background = Ui.rounded(this, Ui.FIELD, 16, Ui.ACCENT, 2)
                }
                else -> {
                    c.text = (i + 1).toString()
                    c.setTextColor(Ui.MUTED)
                    c.background = Ui.rounded(this, Ui.FIELD, 16, Ui.BORDER, 1)
                }
            }
            stepLabels[i].setTextColor(if (i == current) Ui.TEXT else Ui.MUTED)
            stepLabels[i].typeface = if (i == current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            leftHalves[i].setBackgroundColor(if (i > 0 && iss[i - 1].isEmpty()) Ui.OK else Ui.BORDER)
            rightHalves[i].setBackgroundColor(if (i < LAST && iss[i].isEmpty()) Ui.OK else Ui.BORDER)
        }
    }

    // ---------- actions ----------

    private fun save() {
        for ((k, e) in edits) Store.put(this, k, e.text.toString())
        adSp.save()
        imageSp.save()
        subnetSp.save()
        Store.put(this, "preset", preset.toString())
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
            Store.put(this, "ssh_gen_pub", g.openSshPublic.trim())
            Store.put(this, "ssh_ack", "")
            Store.put(this, "ssh", g.openSshPublic)
            ui { edits["ssh"]!!.setText(g.openSshPublic) }
            AppLog.add("Paire SSH générée. Télécharge la clé privée avant de continuer.")
        }
    }

    private fun connect() {
        if (connecting) return
        save()
        val cfg = Store.config(this)
        val miss = Store.missingForLogin(cfg)
        if (miss.isNotEmpty()) {
            AppLog.add("Il manque : " + miss.joinToString(", "))
            Toast.makeText(this, "Il manque : " + miss.joinToString(", "), Toast.LENGTH_LONG).show()
            return
        }
        val sig = currentSig()
        connecting = true
        connError = ""
        bg("Connexion à Oracle (${cfg.region})…") {
            try {
                val key = try {
                    KeyUtil.loadPrivate(cfg.privateKeyPem)
                } catch (e: Exception) {
                    connError = "Clé privée illisible : colle la clé complète, de « -----BEGIN … » à « -----END … ». (${e.javaClass.simpleName})"
                    AppLog.add("⛔ $connError")
                    return@bg
                }

                // L'empreinte doit venir de la clé privée : on la recalcule et on compare à celle saisie.
                val computed = try { KeyUtil.fingerprintOfPrivatePem(cfg.privateKeyPem) } catch (e: Exception) { "" }
                var use = cfg
                if (computed.isNotEmpty() && computed != cfg.fingerprint) {
                    AppLog.add("Empreinte saisie ${cfg.fingerprint} ≠ empreinte de la clé privée $computed : on utilise celle de la clé.")
                    use = cfg.copy(fingerprint = computed)
                    Store.put(this, "fingerprint", computed)
                    ui { edits["fingerprint"]?.setText(computed) }
                }
                val r = OciClient.availabilityDomains(use, key)
                if (r.code !in 200..299) {
                    Store.put(this, "connected_sig", "")
                    connError = "Refusé par Oracle (${r.code}) : " + OciClient.explain(r) +
                        (if (r.code == 401) "\n\nEmpreinte de ta clé privée : $computed\nElle doit être identique à celle affichée dans la console Oracle (Clés API). Sinon, la clé privée collée n'est pas celle enregistrée : génère une nouvelle clé dans l'appli et ajoute sa clé publique dans la console." else "")
                    AppLog.add("⛔ $connError")
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
                    val ri = OciClient.listImages(use, key, page)
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
                val rs = OciClient.listSubnets(use, key)
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
                Store.put(this, "connected_sig", sig)
                ui {
                    adSp.set(ads)
                    subnetSp.set(subnets)
                    setImages(images)
                    updateNav()
                }
                AppLog.add("✅ Connecté : ${ads.size} domaine(s), ${images.size} système(s), ${subnets.size} sous-réseau(x)")
                if (subnets.isEmpty()) {
                    AppLog.add("Aucun sous-réseau : dans la console Oracle, lance l'assistant « Créer un VCN avec connectivité Internet », puis reconnecte-toi.")
                }
            } catch (e: Exception) {
                connError = "Erreur réseau ou inattendue : ${e.javaClass.simpleName} ${e.message ?: ""}"
                AppLog.add("⛔ $connError")
            } finally {
                connecting = false
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
        val running = GrabState.running
        tick++
        if (managerOpen && tick % 8 == 0 && !loadingMachines &&
            machines.any { it.state != "RUNNING" && it.state != "STOPPED" }
        ) loadMachines()

        // étape Compte
        val connected = Store.get(this, "connected_sig") == currentSig() && txt("key").isNotEmpty()
        connBadge.value = when {
            connecting -> "● Connexion en cours…"
            connected -> "● Connecté"
            else -> "● Non connecté"
        }
        connBadge.setTextColor(if (connecting) Ui.WARN else if (connected) Ui.OK else Ui.MUTED)
        val err = connError
        connInfo.value = when {
            connected -> "${adSp.opts.size} domaine(s) · ${allImages.size} système(s) · ${subnetSp.opts.size} sous-réseau(x)"
            err.isNotEmpty() && !connecting -> err
            else -> ""
        }
        connInfo.setTextColor(if (!connected && err.isNotEmpty()) Ui.ERR else Ui.MUTED)

        // étape SSH
        val k = txt("ssh")
        val generated = k.isNotEmpty() && k == Store.get(this, "ssh_gen_pub")
        ackBox.isEnabled = generated
        ackBox.alpha = if (generated) 1f else 0.4f
        val acked = generated && Store.get(this, "ssh_ack") == k
        if (ackBox.isChecked != acked) ackBox.isChecked = acked

        // étape Protection
        val bat = batteryOk()
        batteryView.value = if (bat) "● Autorisé" else "● Non autorisé"
        batteryView.setTextColor(if (bat) Ui.OK else Ui.WARN)
        val adm = dpm().isAdminActive(adminComp())
        adminView.value = if (adm) "● Actif" else "● Inactif"
        adminView.setTextColor(if (adm) Ui.OK else Ui.MUTED)

        // étape Recherche
        val img = imageSp.selected()
        summaryView.value = "Région : ${txt("region")}\n" +
            "Système : ${img?.label ?: "-"}\n" +
            "Puissance : ${preset + 1} cœur(s) · ${6 * (preset + 1)} Go de RAM\n" +
            "Disque : ${txt("boot")} Go · nom : ${txt("name")}\n" +
            "Nouvel essai toutes les ${txt("interval")} s"
        statusTitle.value = if (running) "● Recherche en cours" else "● Recherche à l'arrêt"
        statusTitle.setTextColor(if (running) Ui.OK else Ui.MUTED)
        statusDetail.value = if (running) "Essai n°${GrabState.attempts} · ${GrabState.status}" else "Prête à démarrer."
        enable(btnStart, !running)
        enable(btnStop, running)
        val cmd = sshCommand()
        ipView.value = if (cmd.isEmpty()) "Aucune machine obtenue pour l'instant."
        else "IP : ${Store.get(this, "last_ip")}\n$cmd"
        logView.value = AppLog.text().ifEmpty { "Rien pour l'instant." }

        // Si une étape déjà passée devient invalide, on revient dessus.
        if (!running) {
            val b = blockedIndex()
            if (current > b) {
                Toast.makeText(this, "Il faut d'abord terminer « ${stepNames[b]} »", Toast.LENGTH_SHORT).show()
                show(b)
                return
            }
        }
        updateNav()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (managerOpen) closeManager() else super.onBackPressed()
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
