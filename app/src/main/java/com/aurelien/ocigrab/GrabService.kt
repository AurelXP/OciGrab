package com.aurelien.ocigrab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject
import java.io.IOException

class GrabService : Service() {

    companion object {
        const val NOTIF_ID = 1
        const val FOUND_ID = 2
    }

    private val handler = Handler(Looper.getMainLooper())
    private var worker: Thread? = null

    // Numéro de la recherche en cours : un ancien worker qui se termine ne touche pas à la nouvelle.
    @Volatile private var gen = 0

    // Toutes les secondes : vérifie que la notification est bien là, la remet sinon, et la met à jour
    private val ticker = object : Runnable {
        override fun run() {
            if (!GrabState.running) return
            refreshNotif()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            GrabState.running = false
            gen++
            handler.removeCallbacks(ticker)
            worker?.interrupt()
            AppLog.add("Arrêté manuellement")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        createChannels()
        try {
            startFg(buildNotif("Recherche d'une machine…", true))
        } catch (e: Exception) {
            // Android 12+ refuse parfois le passage au premier plan depuis l'arrière-plan
            AppLog.add("Impossible de passer au premier plan : ${e.javaClass.simpleName}. Ouvre l'appli puis relance la recherche.")
            GrabState.running = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!GrabState.running) {
            // nouvelle recherche : on oublie la machine précédente
            Store.put(this, "last_ip", "")
            Store.put(this, "last_instance", "")
            GrabState.running = true
            GrabState.attempts = 0
            GrabState.startedAt = System.currentTimeMillis()
            GrabState.status = "démarrage"
            val g = ++gen
            worker = Thread { work(g) }.also { it.start() }
            handler.removeCallbacks(ticker)
            handler.postDelayed(ticker, 1000)
        }
        return START_STICKY
    }

    private fun startFg(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun refreshNotif() {
        val secs = (System.currentTimeMillis() - GrabState.startedAt) / 1000
        val time = "%02d:%02d:%02d".format(secs / 3600, (secs / 60) % 60, secs % 60)
        val n = buildNotif("Recherche d'une machine · essai n°${GrabState.attempts} · $time\n${GrabState.status}", true)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val present = try {
            nm.activeNotifications.any { it.id == NOTIF_ID }
        } catch (e: Exception) {
            true
        }
        if (present) nm.notify(NOTIF_ID, n) else try { startFg(n) } catch (e: Exception) { /* on réessaie au prochain tick */ }
    }

    private fun work(g: Int) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ocigrab:wl")
        wl.acquire()
        try {
            val cfg = Store.config(this)
            val missing = Store.missingForLaunch(cfg)
            if (missing.isNotEmpty()) {
                AppLog.add("Config incomplète : " + missing.joinToString(", "))
                return
            }
            val key = KeyUtil.loadPrivate(cfg.privateKeyPem)
            AppLog.add("Démarrage : ${cfg.ocpus} cœurs / ${cfg.memoryGb} Go (${cfg.region}), essai toutes les ${cfg.intervalSec}s")
            while (GrabState.running && g == gen) {
                GrabState.attempts++
                val n = GrabState.attempts
                var wait = cfg.intervalSec * 1000L
                try {
                    val r = OciClient.launch(cfg, key)
                    when {
                        r.code in 200..299 -> {
                            AppLog.add("✅ MACHINE OBTENUE (essai $n) !")
                            val id = try { JSONObject(r.body).optString("id") } catch (e: Exception) { "" }
                            notifyFound("Machine Oracle obtenue !", "L'instance ARM a été créée. Récupération de l'IP…")
                            if (id.isNotEmpty()) awaitIp(cfg, key, id, g)
                            return
                        }
                        r.code == 429 -> {
                            wait = 300_000L
                            GrabState.status = "429 trop de requêtes, pause 5 min"
                            AppLog.add("#$n  429 trop de requêtes, pause 5 min")
                        }
                        r.code in listOf(500, 502, 503, 504, 409) -> {
                            val cap = r.body.contains("capacity", ignoreCase = true)
                            GrabState.status = if (cap) "plus de capacité, on réessaie" else "erreur ${r.code}, on réessaie"
                            AppLog.add("#$n  ${r.code} " + if (cap) "plus de capacité" else r.body.take(120))
                        }
                        else -> {
                            AppLog.add("⛔ Erreur ${r.code} (config à corriger) : " + OciClient.explain(r))
                            notifyFound("OCI Grab arrêté", "Erreur ${r.code} : vérifie la config.")
                            return
                        }
                    }
                } catch (e: IOException) {
                    GrabState.status = "réseau : ${e.message}"
                    AppLog.add("#$n  réseau : ${e.message}")
                }
                Thread.sleep(wait + (0..10_000).random())
            }
        } catch (e: InterruptedException) {
            // arrêt demandé
        } catch (e: Exception) {
            AppLog.add("Erreur : ${e.javaClass.simpleName} ${e.message}")
        } finally {
            if (wl.isHeld) wl.release()
            if (g == gen) {
                GrabState.running = false
                handler.removeCallbacks(ticker)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun awaitIp(cfg: OciConfig, key: java.security.PrivateKey, id: String, g: Int) {
        Store.put(this, "last_instance", id)
        Store.put(this, "last_user", cfg.osUser)
        GrabState.status = "machine créée, attente de l'IP…"
        repeat(45) {
            if (!GrabState.running || g != gen) return
            Thread.sleep(8000)
            try {
                val st = OciClient.getInstance(cfg, key, id)
                if (st.code in 200..299) {
                    val state = JSONObject(st.body).optString("lifecycleState")
                    GrabState.status = "machine : $state"
                    if (state == "RUNNING") {
                        val ip = OciClient.publicIp(cfg, key, id)
                        if (ip != null) {
                            Store.put(this, "last_ip", ip)
                            AppLog.add("🌐 IP publique : $ip")
                            AppLog.add("ssh -i oci_ssh_key.pem ${cfg.osUser}@$ip")
                            notifyFound("Machine prête !", "ssh ${cfg.osUser}@$ip")
                            return
                        }
                    }
                }
            } catch (e: IOException) {
                // on réessaie
            }
        }
        AppLog.add("IP pas encore disponible : regarde la console Oracle ou le bouton « Rafraîchir l'IP ».")
    }

    private fun notifyFound(title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, "found")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(FOUND_ID, n)
    }

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("grab", "Recherche en cours", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("found", "Résultat", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun buildNotif(text: String, withStop: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, "grab")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Oracle Cloud Grabber and Manager")
            .setContentText(text.substringBefore("\n"))
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (withStop) {
            val stopPi = PendingIntent.getService(
                this, 1, Intent(this, GrabService::class.java).setAction("STOP"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Arrêter", stopPi
                ).build()
            )
        }
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    override fun onDestroy() {
        GrabState.running = false
        handler.removeCallbacks(ticker)
        worker?.interrupt()
        super.onDestroy()
    }
}
