// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.VpnService
import android.net.Uri
import android.provider.Settings
import android.os.*
import android.security.KeyChain
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    companion object { const val ACTION_START = "app.lumadrive.START"; const val ACTION_VPN = "app.lumadrive.VPN" }
    private val handler = Handler(Looper.getMainLooper())
    private val dark get() = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val ink get() = if(dark) Color.rgb(240,245,237) else Color.rgb(26,43,30)
    private val muted get() = if(dark) Color.rgb(157,177,163) else Color.rgb(79,101,84)
    private val mint get() = if(dark) Color.rgb(213,237,168) else Color.rgb(57,96,39)
    private val base get() = if(dark) Color.rgb(12,18,15) else Color.rgb(243,247,238)
    private val panel get() = if(dark) Color.rgb(23,32,25) else Color.rgb(229,237,222)
    private lateinit var state: TextView; private lateinit var detail: TextView; private lateinit var address: TextView
    private lateinit var network: TextView; private lateinit var echo: TextView; private lateinit var primary: Button
    private lateinit var connect: Button; private lateinit var disconnect: Button; private lateinit var test: Button
    private lateinit var weatherInfo: TextView; private lateinit var weatherButton: Button
    private lateinit var resolution: Spinner
    private var pending = 0
    private var startGeneration = 0
    private val refresh = object : Runnable {
        override fun run() {
            val receiver = ProjectionService.instance?.receiver
            val active = receiver?.isRunning == true
            val live = receiver?.phase == "Streaming Android Auto"
            state.text = if (ProjectionService.instance?.waitingForClients == true) "Waiting for a display" else if (live) "Connected" else if (active) "Getting ready…" else "Ready for the drive"
            detail.text = receiver?.phase ?: "Enable your hotspot, then tap Start drive."
            primary.text = if (active) "Stop drive" else "Start drive"
            network.text = "Connected clients: ${ProjectionService.instance?.activeClientCount ?: 0}\nTesla access: ${LocalAccessService.status}\n${LocalNameResponder.status}"
            connect.text = if (LocalAccessService.instance?.wanted == true) "Repair VPN" else "Connect VPN"
            disconnect.isEnabled = LocalAccessService.instance?.wanted == true || LocalAccessService.addressPresent()
            val local = LocalAccessService.instance
            val testState = local?.echoState ?: EchoTestSession.State.OFF
            val changing = testState == EchoTestSession.State.STARTING || testState == EchoTestSession.State.STOPPING
            test.text = when(testState) {
                EchoTestSession.State.STARTING -> "Starting test…"
                EchoTestSession.State.STOPPING -> "Stopping test…"
                EchoTestSession.State.RUNNING -> "Stop connection test"
                else -> "Start connection test"
            }
            test.isEnabled = !changing
            echo.text = if (testState == EchoTestSession.State.RUNNING) "http://100.99.9.9:9093/test\n${local?.counts()}"
                else if (testState == EchoTestSession.State.OFF) "Connection test is off\n" else "${local?.counts()}\n"
            val service = ProjectionService.instance
            address.text = if (service != null) service.browserAddresses().joinToString("\n") else "Your browser address appears after Start drive."
            val weatherEnabled = getSharedPreferences("weather",MODE_PRIVATE).getBoolean("enabled",false)
            weatherInfo.text = if (weatherEnabled) service?.weather?.locationStatus ?: "Weather will resume after Start drive" else "Weather from your phone's approximate location."
            weatherButton.text = if (weatherEnabled) "Turn weather off" else "Enable phone weather"
            handler.postDelayed(this, if(changing)100 else 1000)
        }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun background(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(20).toFloat() }
    private fun label(t: String, size: Float, color: Int = ink) = TextView(this).apply { text=t; textSize=size; setTextColor(color) }
    private fun column() = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun LinearLayout.gap(n: Int) { addView(View(this@MainActivity), LinearLayout.LayoutParams(1,dp(n))) }
    private fun LinearLayout.card(content: LinearLayout) { addView(content.apply { background=background(panel);setPadding(dp(20),dp(20),dp(20),dp(20)) },LinearLayout.LayoutParams(-1,-2));gap(14) }
    private fun button(t: String, accent: Boolean = false, action: () -> Unit) = Button(this).apply {
        text=t;isAllCaps=false;textSize=15f
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled),intArrayOf()),intArrayOf(if(accent)base else ink,if(dark)Color.rgb(88,106,94) else Color.rgb(123,139,122))))
        val fill=StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled),background(panel))
            addState(intArrayOf(),background(if(accent)mint else if(dark)Color.rgb(34,46,37) else Color.rgb(211,224,201)))
        }
        background=RippleDrawable(ColorStateList.valueOf(if(dark && !accent)Color.argb(55,255,255,255) else Color.argb(40,0,0,0)),fill,background(Color.WHITE))
        minHeight=dp(50);stateListAnimator=null
        setOnClickListener {
            action()
            handler.removeCallbacks(refresh);handler.postDelayed(refresh,100)
        }
    }
    private fun LinearLayout.action(t: String, run: () -> Unit): Button = button(t, action=run).also { addView(it,LinearLayout.LayoutParams(-1,dp(52)));gap(8) }
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if(dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState);pending=savedInstanceState?.getInt("pending") ?: 0
        window.statusBarColor=base;window.navigationBarColor=base
        window.decorView.systemUiVisibility=if(dark)0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root=column().apply { setPadding(dp(20),dp(26),dp(20),dp(24)) }
        val scroll=ScrollView(this).apply { setBackgroundColor(base);isFillViewport=true;addView(root) }
        scroll.setOnApplyWindowInsetsListener { _, i -> root.setPadding(dp(20),i.systemWindowInsetTop+dp(20),dp(20),i.systemWindowInsetBottom+dp(24));i }
        root.addView(label("Luma Drive",30f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) });root.gap(6)
        root.addView(label("Phone to dashboard.",14f,muted));root.gap(22)
        root.card(column().apply {
            state=label("Ready for the drive",25f);addView(state);gap(10)
            detail=label("",14f,muted);addView(detail);gap(16)
            primary=button("Start drive",true) { if(ProjectionService.instance?.receiver?.isRunning==true) stopDrive() else requestStart(2) }
            addView(primary,LinearLayout.LayoutParams(-1,dp(58)));gap(10)
            action("Stop all Luma services") { stopDrive() }
            action("Android Auto setup") { openAa() }
            addView(label("After a phone restart: Android Auto menu → Start head unit server. Luma reconnects when it is ready.",12f,muted))
        })
        root.card(column().apply {
            addView(label("OPEN IN YOUR CAR",11f,mint));gap(12)
            address=label("",17f).apply { setTextIsSelectable(true) };addView(address);gap(10)
            addView(label("Try the local name first. If the car cannot resolve it, use the numeric address. Pair once with a code.",13f,muted));gap(10)
            action("Pair code") { pairingCode() }
            action("Copy browser link") {
                val url=ProjectionService.instance?.browserAddresses()?.firstOrNull()
                if(url==null) toast("Start drive first") else {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Luma browser",url));toast("Link copied")
                }
            }
        })
        root.card(column().apply {
            addView(label("LOCAL WEATHER",11f,mint));gap(10)
            weatherInfo=label("",13f,muted);addView(weatherInfo);gap(10)
            weatherButton=action("Enable phone weather") { toggleWeather() }
        })
        root.card(column().apply {
            network=label("",16f);addView(network);gap(12)
            connect=action("Connect VPN") { requestStart(1) }
            disconnect=action("Disconnect VPN") {
                LocalAccessService.disconnect(this@MainActivity)
                handler.removeCallbacks(refresh);handler.post(refresh)
            }
            addView(label("Local access on your phone. No remote VPN server.",12f,muted));gap(14)
            test=action("Start connection test") {
                val local = LocalAccessService.instance
                if(local?.echoState == EchoTestSession.State.RUNNING) local.stopEcho()
                else if(local != null && LocalAccessService.addressPresent()) local.startEcho()
                else requestStart(3)
                handler.removeCallbacks(refresh); handler.post(refresh)
            }
            echo=label("",13f,muted).apply { setTextIsSelectable(true); minLines=2 };addView(echo)
        })
        val settings=column().apply { visibility=View.GONE }
        root.action("Settings & diagnostics") { settings.visibility=if(settings.visibility==View.VISIBLE)View.GONE else View.VISIBLE }
        root.addView(settings)
        settings.apply {
            addView(Switch(this@MainActivity).apply {
                text="Start hotspot automatically";setTextColor(ink)
                isChecked=getSharedPreferences("startup",MODE_PRIVATE).getBoolean("hotspot",false)
                setOnCheckedChangeListener { _, checked -> getSharedPreferences("startup",MODE_PRIVATE).edit().putBoolean("hotspot",checked).apply() }
            });gap(10)
            addView(label("Optional on Android 16+. Android may ask for system-settings permission. Other phones use Hotspot settings.",12f,muted));gap(10)
            resolution=Spinner(this@MainActivity).apply {
                adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("720p · balanced","1080p · sharpest"))
                setSelection(if(getSharedPreferences("display",MODE_PRIVATE).getInt("resolution",720)==1080)1 else 0)
                onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?,v: View?,position: Int,id: Long) { getSharedPreferences("display",MODE_PRIVATE).edit().putInt("resolution",if(position==1)1080 else 720).apply() }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
            };addView(resolution);gap(10)
            addView(label("PCM stereo · 48 kHz / 16-bit. Canvas AA display. Voice input is unavailable.",13f,muted));gap(10)
            action("Hotspot settings") { startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) }
            action("Connection history") { AlertDialog.Builder(this@MainActivity).setTitle("Connection history").setMessage(org.json.JSONObject().put("events",RecoveryJournal.read(this@MainActivity)).put("playbackFaults",RecoveryJournal.readPlayback(this@MainActivity)).toString(2)).setPositiveButton("Done",null).show() }
            action("Client certificate") {
                KeyChain.choosePrivateKeyAlias(this@MainActivity,{ alias -> if(alias!=null) getSharedPreferences("identity",MODE_PRIVATE).edit().putString("keyChainAlias",alias).apply() },arrayOf("RSA"),null,null,-1,null)
            }
            action("Keep running in background") { moveTaskToBack(true) }
        }
        root.gap(18);root.addView(label("Luma Drive · ${BuildConfig.VERSION_NAME}",12f,muted))
        setContentView(scroll)
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),1)
        if(savedInstanceState==null && intent?.action==ACTION_START) requestStart(2)
    }
    private fun openAa() { runCatching { startActivity(Intent("com.google.android.projection.gearhead.SETTINGS")) }.onFailure { toast("Open Android Auto from your phone Settings") } }
    private fun toggleWeather() {
        val prefs=getSharedPreferences("weather",MODE_PRIVATE)
        if(prefs.getBoolean("enabled",false)) {
            ProjectionService.instance?.updateWeather(false) ?: prefs.edit().clear().putBoolean("enabled",false).apply()
        } else if(!PhoneWeather.permitted(this)) requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION),89)
        else enableWeather()
    }
    private fun enableWeather() {
        getSharedPreferences("weather",MODE_PRIVATE).edit().putBoolean("enabled",true).apply()
        ProjectionService.instance?.updateWeather(true) ?: toast("Weather enabled. Tap Start drive to get the forecast.")
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==89) {
            if(PhoneWeather.permitted(this))enableWeather() else toast("Weather stays off. You can enable it later.")
            handler.removeCallbacks(refresh);handler.post(refresh)
        }
    }
    private fun toast(t: String) { Toast.makeText(this,t,Toast.LENGTH_LONG).show() }
    private fun stopDrive() {
        pending=0;startGeneration++
        HotspotControl.stopOwned(this)
        stopService(Intent(this,ProjectionService::class.java));LocalAccessService.disconnect(this)
    }
    private fun requestStart(action: Int) {
        pending=action;val generation=++startGeneration
        if(action==2 && getSharedPreferences("startup",MODE_PRIVATE).getBoolean("hotspot",false)) {
            if(!HotspotControl.supported()) { toast("Enable the hotspot in phone settings, then return to Luma.");prepareVpn();return }
            if(!Settings.System.canWrite(this)) {
                startActivityForResult(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,Uri.parse("package:$packageName")),78);return
            }
            HotspotControl.start(this) { ok -> runOnUiThread {
                if(generation!=startGeneration || isDestroyed)return@runOnUiThread
                if(!ok) toast("Android could not start the hotspot. Enable it in phone settings.")
                prepareVpn()
            } };return
        }
        prepareVpn()
    }
    private fun prepareVpn() {
        val consent=VpnService.prepare(this)
        if(consent!=null) startActivityForResult(consent,77) else launchPending()
    }
    private fun launchPending() {
        val requested=pending;pending=0
        if(requested==0)return
        try {
            startForegroundService(Intent(this,LocalAccessService::class.java).setAction(if(requested==3)"start-test" else if(requested==1 && LocalAccessService.instance?.wanted==true)"repair" else "start"))
            if(requested==2) {
                startForegroundService(Intent(this,ProjectionService::class.java).putExtra("resolution",getSharedPreferences("display",MODE_PRIVATE).getInt("resolution",720)))
            }
        } catch(_: RuntimeException) { toast("Could not start. Open Luma and try again.") }
    }
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==78) {
            if(Settings.System.canWrite(this))requestStart(2)
            else { getSharedPreferences("startup",MODE_PRIVATE).edit().putBoolean("hotspot",false).apply();toast("Automatic hotspot is off. Enable your hotspot manually.");prepareVpn() }
        }
        if(requestCode==77) { if(resultCode==RESULT_OK)launchPending() else {pending=0;toast("Connection permission declined. Tap Connect VPN to try again.")} }
    }
    private fun pairingCode() {
        val service=ProjectionService.instance ?: return toast("Start drive first")
        AlertDialog.Builder(this).setTitle("Pair your browser").setMessage("${service.pairing.issue()}\n\nEnter this code in the car within two minutes.").setPositiveButton("Done",null).show()
    }
    override fun onSaveInstanceState(out: Bundle) { out.putInt("pending",pending);super.onSaveInstanceState(out) }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i);setIntent(i);if(i.action==ACTION_START)requestStart(2) }
    override fun onResume() { super.onResume();ProjectionService.instance?.updateWeather();handler.removeCallbacks(refresh);handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh);super.onPause() }
}
