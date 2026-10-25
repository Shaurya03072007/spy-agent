package s.p.y.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

class AdbDnsScanner(private val context: Context) {

    companion object {
        private const val TAG = "AdbDnsScanner"
        const val SERVICE_TYPE_CONNECT = "_adb-tls-connect._tcp."
        const val SERVICE_TYPE_PAIR = "_adb-tls-pairing._tcp."
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val _discoveredConnectPort = MutableStateFlow<Int?>(null)
    val discoveredConnectPort: StateFlow<Int?> = _discoveredConnectPort.asStateFlow()

    private val _discoveredPairPort = MutableStateFlow<Int?>(null)
    val discoveredPairPort: StateFlow<Int?> = _discoveredPairPort.asStateFlow()

    private var connectListener: NsdManager.DiscoveryListener? = null
    private var pairListener: NsdManager.DiscoveryListener? = null

    // Serialized resolve queue to prevent NsdManager FAILURE_ALREADY_ACTIVE
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var isResolving = false
    private val resolveLock = Any()

    fun startDiscovery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || nsdManager == null) return

        stopDiscovery()

        connectListener = createDiscoveryListener(SERVICE_TYPE_CONNECT)
        pairListener = createDiscoveryListener(SERVICE_TYPE_PAIR)

        try {
            nsdManager.discoverServices(SERVICE_TYPE_CONNECT, NsdManager.PROTOCOL_DNS_SD, connectListener)
            Log.i(TAG, "NSD discovery started for connect service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NSD connect discovery", e)
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE_PAIR, NsdManager.PROTOCOL_DNS_SD, pairListener)
            Log.i(TAG, "NSD discovery started for pair service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NSD pair discovery", e)
        }
    }

    fun stopDiscovery() {
        if (nsdManager == null) return
        try {
            connectListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (_: Exception) {}
        try {
            pairListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (_: Exception) {}
        connectListener = null
        pairListener = null

        synchronized(resolveLock) {
            resolveQueue.clear()
            isResolving = false
        }
    }

    private fun createDiscoveryListener(expectedServiceType: String): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "NSD Discovery started for $regType (expected: $expectedServiceType)")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD Service found: name=${serviceInfo.serviceName}, type=${serviceInfo.serviceType}")
                enqueueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD Service lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "NSD Discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD Start discovery failed for $serviceType: error $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD Stop discovery failed for $serviceType: error $errorCode")
            }
        }
    }

    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        synchronized(resolveLock) {
            resolveQueue.add(serviceInfo)
            processNextResolve()
        }
    }

    private fun processNextResolve() {
        synchronized(resolveLock) {
            if (isResolving || resolveQueue.isEmpty()) return
            val nextService = resolveQueue.poll() ?: return
            isResolving = true

            try {
                nsdManager?.resolveService(nextService, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                        synchronized(resolveLock) {
                            isResolving = false
                            processNextResolve()
                        }
                    }

                    override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                        val port = resolvedInfo.port
                        val type = (resolvedInfo.serviceType ?: "").lowercase()
                        val name = (resolvedInfo.serviceName ?: "").lowercase()

                        Log.i(TAG, "Successfully resolved service: name=$name, type=$type, port=$port")

                        if (port in 1..65535) {
                            val isPairing = type.contains("pairing") || name.contains("pair") || name.contains("adb-tls-pairing")
                            val isConnect = type.contains("connect") || name.contains("connect") || name.contains("adb-tls-connect")

                            if (isPairing && !isConnect) {
                                Log.i(TAG, "Setting Discovered Pairing Port -> $port")
                                _discoveredPairPort.value = port
                            } else if (isConnect && !isPairing) {
                                Log.i(TAG, "Setting Discovered Connect Port -> $port")
                                _discoveredConnectPort.value = port
                            } else if (isPairing) {
                                Log.i(TAG, "Setting Discovered Pairing Port (fallback) -> $port")
                                _discoveredPairPort.value = port
                            } else if (isConnect) {
                                Log.i(TAG, "Setting Discovered Connect Port (fallback) -> $port")
                                _discoveredConnectPort.value = port
                            }
                        }

                        synchronized(resolveLock) {
                            isResolving = false
                            processNextResolve()
                        }
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "Error initiating resolveService", e)
                isResolving = false
                processNextResolve()
            }
        }
    }
}
