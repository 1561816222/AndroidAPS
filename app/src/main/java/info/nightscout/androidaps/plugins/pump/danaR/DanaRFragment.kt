package info.nightscout.androidaps.plugins.pump.danaR

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import dagger.android.support.DaggerFragment
import info.nightscout.androidaps.R
import info.nightscout.androidaps.activities.TDDStatsActivity
import info.nightscout.androidaps.dialogs.ProfileViewerDialog
import info.nightscout.androidaps.events.EventExtendedBolusChange
import info.nightscout.androidaps.events.EventInitializationChanged
import info.nightscout.androidaps.events.EventPumpStatusChanged
import info.nightscout.androidaps.events.EventTempBasalChange
import info.nightscout.androidaps.interfaces.ActivePluginProvider
import info.nightscout.androidaps.interfaces.CommandQueueProvider
import info.nightscout.androidaps.interfaces.PluginType
import info.nightscout.androidaps.interfaces.PumpInterface
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.plugins.bus.RxBusWrapper
import info.nightscout.androidaps.plugins.pump.danaR.activities.DanaRHistoryActivity
import info.nightscout.androidaps.plugins.pump.danaR.activities.DanaRUserOptionsActivity
import info.nightscout.androidaps.plugins.pump.danaR.events.EventDanaRNewStatus
import info.nightscout.androidaps.plugins.pump.danaRKorean.DanaRKoreanPlugin
import info.nightscout.androidaps.plugins.pump.danaRS.DanaRSPlugin
import info.nightscout.androidaps.queue.events.EventQueueChanged
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.FabricPrivacy
import info.nightscout.androidaps.utils.SetWarnColor
import info.nightscout.androidaps.utils.T
import info.nightscout.androidaps.utils.alertDialogs.OKDialog
import info.nightscout.androidaps.utils.extensions.plusAssign
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.androidaps.utils.sharedPreferences.SP
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import kotlinx.android.synthetic.main.danar_fragment.*
import javax.inject.Inject

class DanaRFragment : DaggerFragment() {
    @Inject lateinit var rxBus: RxBusWrapper
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var commandQueue: CommandQueueProvider
    @Inject lateinit var activePlugin: ActivePluginProvider
    @Inject lateinit var danaRKoreanPlugin: DanaRKoreanPlugin
    @Inject lateinit var danaRSPlugin: DanaRSPlugin
    @Inject lateinit var danaRPump: DanaRPump
    @Inject lateinit var resourceHelper: ResourceHelper
    @Inject lateinit var sp: SP

    private var disposable: CompositeDisposable = CompositeDisposable()

    private val loopHandler = Handler()
    private lateinit var refreshLoop: Runnable

    init {
        refreshLoop = Runnable {
            activity?.runOnUiThread { updateGUI() }
            loopHandler.postDelayed(refreshLoop, T.mins(1).msecs())
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.danar_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dana_pumpstatus.setBackgroundColor(resourceHelper.gc(R.color.colorInitializingBorder))

        danar_history.setOnClickListener { startActivity(Intent(context, DanaRHistoryActivity::class.java)) }
        danar_viewprofile.setOnClickListener {
            fragmentManager?.let { fragmentManager ->
                val profile = danaRPump.createConvertedProfile()?.getDefaultProfile()
                    ?: return@let
                val profileName = danaRPump.createConvertedProfile()?.getDefaultProfileName()
                    ?: return@let
                val args = Bundle()
                args.putLong("time", DateUtil.now())
                args.putInt("mode", ProfileViewerDialog.Mode.CUSTOM_PROFILE.ordinal)
                args.putString("customProfile", profile.data.toString())
                args.putString("customProfileUnits", profile.units)
                args.putString("customProfileName", profileName)
                val pvd = ProfileViewerDialog()
                pvd.arguments = args
                pvd.show(fragmentManager, "ProfileViewDialog")
            }
        }
        danar_stats.setOnClickListener { startActivity(Intent(context, TDDStatsActivity::class.java)) }
        danar_user_options.setOnClickListener { startActivity(Intent(context, DanaRUserOptionsActivity::class.java)) }
        danar_btconnection.setOnClickListener {
            aapsLogger.debug(LTag.PUMP, "Clicked connect to pump")
            danaRPump.lastConnection = 0
            commandQueue.readStatus("Clicked connect to pump", null)
        }
        if (danaRSPlugin.isEnabled())
            danar_btconnection.setOnLongClickListener {
                activity?.let {
                    OKDialog.showConfirmation(it, resourceHelper.gs(R.string.resetpairing), Runnable {
                        sp.remove(resourceHelper.gs(R.string.key_danars_pairingkey) + danaRSPlugin.mDeviceName)
                        sp.remove(resourceHelper.gs(R.string.key_danars_v3_randompairingkey) + danaRSPlugin.mDeviceName)
                        sp.remove(resourceHelper.gs(R.string.key_danars_v3_pairingkey) + danaRSPlugin.mDeviceName)
                        sp.remove(resourceHelper.gs(R.string.key_danars_v3_randomsynckey) + danaRSPlugin.mDeviceName)
                    })
                }
                true
            }
    }

    @Synchronized
    override fun onResume() {
        super.onResume()
        loopHandler.postDelayed(refreshLoop, T.mins(1).msecs())
        disposable += rxBus
            .toObservable(EventInitializationChanged::class.java)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ updateGUI() }, { fabricPrivacy.logException(it) })
        disposable += rxBus
            .toObservable(EventDanaRNewStatus::class.java)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ updateGUI() }, { fabricPrivacy.logException(it) })
        disposable += rxBus
            .toObservable(EventExtendedBolusChange::class.java)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ updateGUI() }, { fabricPrivacy.logException(it) })
        disposable += rxBus
            .toObservable(EventTempBasalChange::class.java)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ updateGUI() }, { fabricPrivacy.logException(it) })
        disposable += rxBus
            .toObservable(EventQueueChanged::class.java)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ updateGUI() }, { fabricPrivacy.logException(it) })
        disposable += rxBus
            .toObservable(EventPumpStatusChanged::class.java)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                when {
                    it.status == EventPumpStatusChanged.Status.CONNECTING   ->
                        @Suppress("SetTextI18n")
                        danar_btconnection?.text = "{fa-bluetooth-b spin} ${it.secondsElapsed}s"
                    it.status == EventPumpStatusChanged.Status.CONNECTED    ->
                        @Suppress("SetTextI18n")
                        danar_btconnection?.text = "{fa-bluetooth}"
                    it.status == EventPumpStatusChanged.Status.DISCONNECTED ->
                        @Suppress("SetTextI18n")
                        danar_btconnection?.text = "{fa-bluetooth-b}"
                }
                if (it.getStatus(resourceHelper) != "") {
                    dana_pumpstatus?.text = it.getStatus(resourceHelper)
                    dana_pumpstatuslayout?.visibility = View.VISIBLE
                } else {
                    dana_pumpstatuslayout?.visibility = View.GONE
                }
            }, { fabricPrivacy.logException(it) })
        updateGUI()
    }

    @Synchronized
    override fun onPause() {
        super.onPause()
        disposable.clear()
        loopHandler.removeCallbacks(refreshLoop)
    }

    // GUI functions
    @Synchronized
    fun updateGUI() {
        if (danar_dailyunits == null) return
        val pump = danaRPump
        val plugin: PumpInterface = activePlugin.activePump
        if (pump.lastConnection != 0L) {
            val agoMsec = System.currentTimeMillis() - pump.lastConnection
            val agoMin = (agoMsec.toDouble() / 60.0 / 1000.0).toInt()
            danar_lastconnection.text = DateUtil.timeString(pump.lastConnection) + " (" + resourceHelper.gs(R.string.minago, agoMin) + ")"
            SetWarnColor.setColor(danar_lastconnection, agoMin.toDouble(), 16.0, 31.0)
        }
        if (pump.lastBolusTime != 0L) {
            val agoMsec = System.currentTimeMillis() - pump.lastBolusTime
            val agoHours = agoMsec.toDouble() / 60.0 / 60.0 / 1000.0
            if (agoHours < 6)
            // max 6h back
                danar_lastbolus.text = DateUtil.timeString(pump.lastBolusTime) + " " + DateUtil.sinceString(pump.lastBolusTime, resourceHelper) + " " + resourceHelper.gs(R.string.formatinsulinunits, pump.lastBolusAmount)
            else
                danar_lastbolus.text = ""
        }

        danar_dailyunits.text = resourceHelper.gs(R.string.reservoirvalue, pump.dailyTotalUnits, pump.maxDailyTotalUnits)
        SetWarnColor.setColor(danar_dailyunits, pump.dailyTotalUnits, pump.maxDailyTotalUnits * 0.75, pump.maxDailyTotalUnits * 0.9)
        danar_basabasalrate.text = "( " + (pump.activeProfile + 1) + " )  " + resourceHelper.gs(R.string.pump_basebasalrate, plugin.baseBasalRate)
        // DanaRPlugin, DanaRKoreanPlugin
        if (activePlugin.activePump.isFakingTempsByExtendedBoluses == true) {
            danar_tempbasal.text = activePlugin.activeTreatments.getRealTempBasalFromHistory(System.currentTimeMillis())?.toStringFull()
                ?: ""
        } else {
            // v2 plugin
            danar_tempbasal.text = activePlugin.activeTreatments.getTempBasalFromHistory(System.currentTimeMillis())?.toStringFull()
                ?: ""
        }
        danar_extendedbolus.text = activePlugin.activeTreatments.getExtendedBolusFromHistory(System.currentTimeMillis())?.toString()
            ?: ""
        danar_reservoir.text = resourceHelper.gs(R.string.reservoirvalue, pump.reservoirRemainingUnits, 300)
        SetWarnColor.setColorInverse(danar_reservoir, pump.reservoirRemainingUnits, 50.0, 20.0)
        danar_battery.text = "{fa-battery-" + pump.batteryRemaining / 25 + "}"
        SetWarnColor.setColorInverse(danar_battery, pump.batteryRemaining.toDouble(), 51.0, 26.0)
        danar_iob.text = resourceHelper.gs(R.string.formatinsulinunits, pump.iob)
        danar_firmware.text = resourceHelper.gs(R.string.danar_model, pump.modelFriendlyName(), pump.hwModel, pump.protocol, pump.productCode)
        danar_basalstep.text = pump.basalStep.toString()
        danar_bolusstep.text = pump.bolusStep.toString()
        danar_serialnumber.text = pump.serialNumber
        val status = commandQueue.spannedStatus()
        if (status.toString() == "") {
            danar_queue.visibility = View.GONE
        } else {
            danar_queue.visibility = View.VISIBLE
            danar_queue.text = status
        }
        //hide user options button if not an RS pump or old firmware
        // also excludes pump with model 03 because of untested error
        val isKorean = danaRKoreanPlugin.isEnabled(PluginType.PUMP)
        if (isKorean || pump.hwModel == 0 || pump.hwModel == 3) {
            danar_user_options.visibility = View.GONE
        }
    }
}
